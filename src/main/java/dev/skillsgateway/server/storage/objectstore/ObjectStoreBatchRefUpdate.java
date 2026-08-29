package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * A whole push, evaluated against one manifest read and committed as one conditional write.
 *
 * <p>This is what makes {@code performsAtomicTransactions()} honest, and it is the same mechanism
 * a revocation already needs, applied to N references instead of two. The default
 * {@code BatchRefUpdate} walks the command list making one independent update per reference, which
 * on this backend would be N separate conditional writes and therefore N separate opportunities to
 * publish half a push.
 *
 * <p>Results are computed but not recorded until the write is accepted. The transition is
 * re-evaluated from scratch whenever the store refuses it, so anything written into the commands
 * during evaluation would be written several times and, worse, would survive an attempt that was
 * later abandoned.
 */
final class ObjectStoreBatchRefUpdate extends BatchRefUpdate {

    private final ObjectStoreRefDatabase refdb;
    private final ManifestStore manifests;

    ObjectStoreBatchRefUpdate(ObjectStoreRefDatabase refdb, ManifestStore manifests) {
        super(refdb);
        this.refdb = refdb;
        this.manifests = manifests;
    }

    @Override
    public void execute(RevWalk walk, ProgressMonitor monitor, List<String> options) throws IOException {
        List<ReceiveCommand> pending = new ArrayList<>();
        for (ReceiveCommand command : getCommands()) {
            if (command.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                pending.add(command);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        if (options != null) {
            setPushOptions(options);
        }
        monitor.beginTask("Updating references", pending.size());
        try {
            for (ReceiveCommand command : pending) {
                if (isMissing(walk, command.getOldId()) || isMissing(walk, command.getNewId())) {
                    command.setResult(ReceiveCommand.Result.REJECTED_MISSING_OBJECT);
                    continue;
                }
                command.updateType(walk);
                if (command.getType() == ReceiveCommand.Type.UPDATE_NONFASTFORWARD && !isAllowNonFastForwards()) {
                    command.setResult(ReceiveCommand.Result.REJECTED_NONFASTFORWARD);
                }
            }
            List<ReceiveCommand> applicable = pending.stream()
                    .filter(command -> command.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED)
                    .toList();
            if (applicable.isEmpty()) {
                return;
            }
            Map<ReceiveCommand, ReceiveCommand.Result> results = commit(applicable);
            refdb.invalidate();
            results.forEach(ReceiveCommand::setResult);
        } finally {
            monitor.endTask();
        }
    }

    /**
     * One transition for the whole list. Under {@code atomic} a single refused precondition
     * abandons every command, which is what the capability promises; otherwise the commands whose
     * preconditions hold are still committed together, in one write rather than several.
     */
    private Map<ReceiveCommand, ReceiveCommand.Result> commit(List<ReceiveCommand> commands) throws IOException {
        String description = "push of %d reference(s) (%s)"
                .formatted(
                        commands.size(),
                        commands.stream().map(ReceiveCommand::getRefName).toList());
        return manifests.transact(description, current -> {
            Map<ReceiveCommand, ReceiveCommand.Result> results = new LinkedHashMap<>();
            Map<String, String> edits = new HashMap<>();
            for (ReceiveCommand command : commands) {
                String stored = current.ref(command.getRefName());
                if (!preconditionHolds(command, stored)) {
                    results.put(command, ReceiveCommand.Result.LOCK_FAILURE);
                    continue;
                }
                results.put(command, ReceiveCommand.Result.OK);
                edits.put(command.getRefName(), desiredValue(command));
            }
            boolean refused = results.containsValue(ReceiveCommand.Result.LOCK_FAILURE);
            if (isAtomic() && refused) {
                Map<ReceiveCommand, ReceiveCommand.Result> rejected = new LinkedHashMap<>();
                commands.forEach(command -> rejected.put(command, ReceiveCommand.Result.LOCK_FAILURE));
                return ManifestStore.Step.unchanged(rejected);
            }
            if (edits.isEmpty()) {
                return ManifestStore.Step.unchanged(results);
            }
            return new ManifestStore.Step<>(current.withRefs(edits), results);
        });
    }

    private boolean preconditionHolds(ReceiveCommand command, String stored) {
        if (command.getOldSymref() != null) {
            return (RepositoryManifest.SYMBOLIC_PREFIX + command.getOldSymref()).equals(stored);
        }
        ObjectId oldId = command.getOldId();
        if (oldId == null || ObjectId.zeroId().equals(oldId)) {
            return stored == null;
        }
        return oldId.name().equals(stored);
    }

    private String desiredValue(ReceiveCommand command) {
        if (command.getNewSymref() != null) {
            return RepositoryManifest.SYMBOLIC_PREFIX + command.getNewSymref();
        }
        ObjectId newId = command.getNewId();
        return newId == null || ObjectId.zeroId().equals(newId) ? null : newId.name();
    }

    private static boolean isMissing(RevWalk walk, ObjectId id) throws IOException {
        if (id == null || ObjectId.zeroId().equals(id)) {
            return false;
        }
        try {
            walk.parseAny(id);
            return false;
        } catch (org.eclipse.jgit.errors.MissingObjectException e) {
            return true;
        }
    }

    @Override
    public void execute(RevWalk walk, ProgressMonitor monitor) throws IOException {
        execute(walk, monitor, null);
    }

    @Override
    protected ObjectStoreRefDatabase getRefDatabase() {
        return refdb;
    }
}

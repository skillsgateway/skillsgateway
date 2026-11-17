import { FolderGit2 } from "lucide-react";
import { useFormerHostedRepositories, useIsAdmin, type FormerHostedRepository } from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

/**
 * TEMPORARY. REMOVE-AFTER: two minor releases after the release that ships migration
 * V3__remove_hosted_marketplaces (with the route, `FormerHostedRepositories` and GW_AUTH_0091).
 *
 * What an administrator is told while repositories from the removed hosted-marketplace feature are
 * still in the gateway's data store: each is the only copy of a publisher's source. There is no
 * delete control — the operator copies the folder and deletes it themselves — and the notice ends
 * on its own the moment the list does. Nothing is stored here: the list is read, so it is empty
 * when the folders are gone.
 */
export function FormerHostedList({ repositories }: { repositories: FormerHostedRepository[] }) {
  const count = repositories.length;
  return (
    <Alert role="status" aria-label="Former hosted repositories">
      <FolderGit2 aria-hidden />
      <AlertTitle>This gateway no longer hosts marketplaces</AlertTitle>
      <AlertDescription className="space-y-3">
        <p>
          {count === 1 ? "1 repository" : `${count} repositories`} from the former hosting feature{" "}
          {count === 1 ? "is" : "are"} still in its data store. Copy each one (<code className="rounded bg-muted px-1 font-mono text-xs">git clone &lt;location&gt;</code>) and then delete the folder. This notice goes away by itself when they are gone.
        </p>
        <ul className="space-y-2">
          {repositories.map((repository) => (
            <li key={repository.name} className="space-y-0.5">
              <div className="font-medium break-all">{repository.name}</div>
              <div className="font-mono text-xs break-all text-muted-foreground">{repository.location}</div>
              {repository.lastCommitAt || repository.headSha ? (
                <div className="text-xs text-muted-foreground">
                  last commit{" "}
                  {repository.headSha ? <span className="font-mono">{repository.headSha}</span> : null}
                  {repository.lastCommitAt ? (
                    <>
                      {repository.headSha ? ", " : ""}
                      <Timestamp value={repository.lastCommitAt} dayOnly />
                    </>
                  ) : null}
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      </AlertDescription>
    </Alert>
  );
}

/**
 * The notice itself: for an administrator only, and the only one who causes the request. Renders
 * nothing while the list is loading, if it cannot be read, and when it is empty.
 *
 * @Requirements GW_AUTH_0091
 */
export function FormerHostedNotice() {
  const isAdmin = useIsAdmin();
  const repositories = useFormerHostedRepositories(isAdmin);
  if (!isAdmin || !repositories.data || repositories.data.length === 0) {
    return null;
  }
  return <FormerHostedList repositories={repositories.data} />;
}

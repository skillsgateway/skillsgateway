# Vendored Unicode confusables table

`confusables-18.0.0.txt` is the table `NameNormalizer` folds plugin names
with before the name-collision gate compares them (UTS #39 confusable
skeleton).

## Where it came from

| | |
| --- | --- |
| Standard | Unicode Technical Standard #39, Unicode Security Mechanisms |
| Source | <https://www.unicode.org/Public/security/latest/confusables.txt> |
| Version | 18.0.0 (file dated 2026-08-06) |
| SHA-256 | `6ed3ee967c9dfdf6677d563c9985182fbc50a2efb7d6059cd57b2e2ce18f5b92` |
| Pinned on | 2026-09-23 |
| Licence | Unicode License v3, <https://www.unicode.org/license.txt> |

Vendored verbatim, so the checksum above can be compared with the upstream
file of the same version.

## Bumping the pin

1. Download the new version's `confusables.txt` and record its checksum here.
2. Add it as a **new** file named for its version; delete the old one in the
   same change, and point `NameNormalizer.TABLE` at the new file.
3. Run `NameNormalizerTests`. A bump can make two names that were distinct
   collide, and the reverse: the gate computes keys at decision time and never
   stores them, so the new table takes effect on the next approval.

The gateway never fetches the table at runtime.

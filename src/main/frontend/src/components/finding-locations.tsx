import { Fragment, useContext } from "react";
import { Link } from "react-router-dom";
import { LocationHrefContext } from "@/lib/location-href";
import { SHOWN_LOCATIONS } from "@/lib/vetting-flow";


/** One location: a link to its file and line when the surface has somewhere to send it (GW_APPROVAL_0030). */
export function FindingLocation({ location }: { location: string }) {
  const href = useContext(LocationHrefContext)?.(location) ?? null;
  return href ? (
    <Link
      to={href}
      className="rounded-sm underline decoration-dotted underline-offset-2 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      {location}
    </Link>
  ) : (
    <>{location}</>
  );
}

/**
 * Locations as the report has always written them (`a:1, b:1, c:1 and 4 more`: every location a
 * reviewer is asked about, never a bare rule), with each shown one a link.
 *
 * @Requirements GW_APPROVAL_0030
 */
export function FindingLocations({ locations }: { locations: readonly string[] }) {
  if (locations.length === 0) return <>—</>;
  const shown = locations.slice(0, SHOWN_LOCATIONS);
  const more = locations.length - shown.length;
  return (
    <>
      {shown.map((location, index) => (
        <Fragment key={`${index}:${location}`}>
          {index > 0 ? ", " : null}
          <FindingLocation location={location} />
        </Fragment>
      ))}
      {more > 0 ? ` and ${more} more` : null}
    </>
  );
}

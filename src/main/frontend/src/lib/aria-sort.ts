/** The `aria-sort` value for a column header, from the table's own sort state (`false` when unsorted). */
export function ariaSort(sorted: false | "asc" | "desc"): "ascending" | "descending" | "none" {
  return sorted === "asc" ? "ascending" : sorted === "desc" ? "descending" : "none";
}

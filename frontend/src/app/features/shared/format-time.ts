/**
 * Rendering of server timestamps in the viewer's local time.
 *
 * `spec.md`: "TimeStamp precision is to the minute. TimeZone is UTC on the
 * server; browsers translate to local time." The wire value is therefore always
 * an ISO-8601 UTC instant at minute precision (`2026-09-13T18:30:00Z`), and the
 * only correct thing to put on screen is that instant expressed in whatever zone
 * the reader is sitting in.
 *
 * The numeric parts come from `Intl.DateTimeFormat`, which is the only API that
 * can resolve a named zone's offset for a given instant (including DST), but the
 * month name is assembled here from a fixed table rather than taken from the
 * locale. That keeps the output byte-identical across ICU versions and browser
 * locales, which is what makes the conversion assertable in a test.
 */

const MONTHS = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
] as const;

interface InstantParts {
  readonly year: string;
  readonly month: number;
  readonly day: string;
  readonly hour: string;
  readonly minute: string;
}

function partsOf(date: Date, timeZone?: string): InstantParts | null {
  const formatter = new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    ...(timeZone ? { timeZone } : {}),
  });

  const found: Record<string, string> = {};
  for (const part of formatter.formatToParts(date)) {
    found[part.type] = part.value;
  }
  const { year, month, day, hour, minute } = found;
  if (!year || !month || !day || !hour || !minute) {
    return null;
  }
  return { year, month: Number(month), day, hour, minute };
}

/**
 * Formats a UTC wire timestamp for display, e.g. `13 Sep 2026, 18:30` in UTC
 * becoming `14 Sep 2026, 03:30` for a reader in Tokyo.
 *
 * @param iso the server's ISO-8601 UTC timestamp.
 * @param timeZone an IANA zone name; omitted means the viewer's own zone. It is
 *   a parameter purely so a test can pin a zone and assert the conversion.
 * @returns the localized string, or the input unchanged if it is not a date.
 */
export function formatLocalTimestamp(iso: string, timeZone?: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const parts = partsOf(date, timeZone);
  if (!parts) {
    return iso;
  }
  const month = MONTHS[parts.month - 1] ?? String(parts.month);
  return `${parts.day} ${month} ${parts.year}, ${parts.hour}:${parts.minute}`;
}

/**
 * The `datetime` attribute value for a `<time>` element: the original UTC
 * instant, so assistive technology and machines read the unambiguous value
 * while the text node carries the local rendering.
 */
export function machineTimestamp(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toISOString();
}

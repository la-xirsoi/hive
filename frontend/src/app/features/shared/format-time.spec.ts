import { formatLocalTimestamp, machineTimestamp } from './format-time';

/**
 * The conversion asserted here is the one `spec.md` requires: "TimeZone is UTC
 * on the server; browsers translate to local time." Each case pins an explicit
 * IANA zone so the assertion is about the conversion itself and not about
 * wherever the test machine happens to be.
 */
describe('formatLocalTimestamp', () => {
  const utcInstant = '2026-09-13T18:30:00Z';

  it('renders a UTC instant unchanged in UTC', () => {
    expect(formatLocalTimestamp(utcInstant, 'UTC')).toBe('13 Sep 2026, 18:30');
  });

  it('shifts a UTC instant forward into Tokyo, crossing the date line of the day', () => {
    // UTC+9: 18:30 on the 13th becomes 03:30 on the 14th.
    expect(formatLocalTimestamp(utcInstant, 'Asia/Tokyo')).toBe('14 Sep 2026, 03:30');
  });

  it('shifts a UTC instant back into New York, honouring daylight saving', () => {
    // September is EDT, UTC-4.
    expect(formatLocalTimestamp(utcInstant, 'America/New_York')).toBe('13 Sep 2026, 14:30');
  });

  it('uses the standard-time offset for the same zone in winter', () => {
    // January is EST, UTC-5, so the same wall-clock UTC time lands an hour earlier.
    expect(formatLocalTimestamp('2026-01-13T18:30:00Z', 'America/New_York')).toBe(
      '13 Jan 2026, 13:30',
    );
  });

  it('keeps minute precision without inventing seconds', () => {
    expect(formatLocalTimestamp(utcInstant, 'UTC')).not.toContain(':00:');
  });

  it('falls back to the raw value when the input is not a timestamp', () => {
    expect(formatLocalTimestamp('not a date', 'UTC')).toBe('not a date');
  });

  it('formats in the viewer zone when none is pinned', () => {
    expect(formatLocalTimestamp(utcInstant)).toMatch(/^\d{2} \w{3} 2026, \d{2}:\d{2}$/);
  });
});

describe('machineTimestamp', () => {
  it('keeps the unambiguous UTC instant for the datetime attribute', () => {
    expect(machineTimestamp('2026-09-13T18:30:00Z')).toBe('2026-09-13T18:30:00.000Z');
  });

  it('passes an unparseable value through untouched', () => {
    expect(machineTimestamp('soon')).toBe('soon');
  });
});

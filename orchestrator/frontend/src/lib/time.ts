/**
 * Time alone for today, date and time for anything older — a transcript that
 * spans days should not show two "04:50 PM" turns as if they were minutes
 * apart.
 */
export function formatWhen(ts: string): string {
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return "";

  const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  const now = new Date();
  const isToday =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();

  return isToday ? time : `${d.toLocaleDateString()} ${time}`;
}

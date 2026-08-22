export function formatWon(value: number): string {
  const sign = value > 0 ? '+' : value < 0 ? '-' : '';
  return `${sign}${Math.abs(Math.round(value)).toLocaleString('ko-KR')}원`;
}

export function formatPrice(value: number): string {
  return `${Math.round(value).toLocaleString('ko-KR')}원`;
}

export function formatRate(value: number): string {
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}%`;
}

export function formatDateTime(value: string): string {
  const date = new Date(value);
  return new Intl.DateTimeFormat('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date);
}

export function toNumber(value: string): number {
  return Number(value.replaceAll(',', '').trim());
}

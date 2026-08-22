import type { ColorSchemeName } from 'react-native';

export const spacing = {
  xxs: 4,
  xs: 8,
  sm: 12,
  md: 16,
  lg: 20,
  xl: 24,
  xxl: 32,
  huge: 48,
} as const;

export const radius = { sm: 10, md: 16, lg: 22, pill: 999 } as const;
export const type = {
  caption: 12,
  body: 15,
  bodyStrong: 16,
  subtitle: 20,
  title: 28,
  hero: 36,
} as const;

const light = {
  background: '#F6F7F9',
  surface: '#FFFFFF',
  surfaceMuted: '#EDF0F4',
  surfaceRaised: '#FFFFFF',
  text: '#15191F',
  textMuted: '#68717D',
  textInverse: '#FFFFFF',
  border: '#DDE2E8',
  accent: '#335CFF',
  accentSoft: '#E8EDFF',
  positive: '#087A55',
  positiveSoft: '#DCF6EA',
  negative: '#C83A45',
  negativeSoft: '#FCE7E9',
  warning: '#9A5A00',
  warningSoft: '#FFF0CF',
  overlay: 'rgba(10, 17, 30, 0.45)',
} as const;

const dark = {
  background: '#0C1016',
  surface: '#151B23',
  surfaceMuted: '#202834',
  surfaceRaised: '#19212C',
  text: '#F3F5F8',
  textMuted: '#A6B0BE',
  textInverse: '#FFFFFF',
  border: '#2D3745',
  accent: '#7692FF',
  accentSoft: '#23315F',
  positive: '#48C99A',
  positiveSoft: '#123E32',
  negative: '#FF7C86',
  negativeSoft: '#4D252C',
  warning: '#FFC668',
  warningSoft: '#4A381C',
  overlay: 'rgba(0, 0, 0, 0.65)',
} as const;

export type Palette = { [K in keyof typeof light]: string };
export type ThemeMode = 'system' | 'light' | 'dark';

export function resolvePalette(mode: ThemeMode, system: ColorSchemeName | null): Palette {
  const resolved = mode === 'system' ? system : mode;
  return resolved === 'dark' ? dark : light;
}

export const palettes = { light, dark } as const;

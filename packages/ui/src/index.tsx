import React, { createContext, useContext, useMemo } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
  useColorScheme,
  type PressableProps,
  type ScrollViewProps,
  type TextInputProps,
  type TextProps,
  type ViewProps,
} from 'react-native';
import { palettes, radius, resolvePalette, spacing, type Palette, type ThemeMode, type } from './tokens';

export * from './tokens';

const ThemeContext = createContext<{ colors: Palette; mode: ThemeMode }>({
  colors: palettes.light,
  mode: 'system',
});

export function SignalThemeProvider({ children, mode = 'system' }: { children: React.ReactNode; mode?: ThemeMode }) {
  const system = useColorScheme();
  const value = useMemo(() => ({ colors: resolvePalette(mode, system), mode }), [mode, system]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useSignalTheme() {
  return useContext(ThemeContext);
}

type AppTextProps = TextProps & {
  tone?: 'default' | 'muted' | 'accent' | 'positive' | 'negative' | 'warning' | 'inverse';
  variant?: 'caption' | 'body' | 'bodyStrong' | 'subtitle' | 'title' | 'hero';
};

export function AppText({ style, tone = 'default', variant = 'body', ...props }: AppTextProps) {
  const { colors } = useSignalTheme();
  const tones = {
    default: colors.text,
    muted: colors.textMuted,
    accent: colors.accent,
    positive: colors.positive,
    negative: colors.negative,
    warning: colors.warning,
    inverse: colors.textInverse,
  };
  const weight = ['bodyStrong', 'subtitle', 'title', 'hero'].includes(variant) ? '700' : '400';
  return <Text {...props} style={[styles.text, { color: tones[tone], fontSize: type[variant], fontWeight: weight }, style]} />;
}

export function Screen({ children, contentContainerStyle, ...props }: ScrollViewProps) {
  const { colors } = useSignalTheme();
  return (
    <ScrollView
      {...props}
      style={[{ flex: 1, backgroundColor: colors.background }, props.style]}
      contentContainerStyle={[styles.screen, contentContainerStyle]}
      keyboardShouldPersistTaps="handled"
      showsVerticalScrollIndicator={false}
    >
      {children}
    </ScrollView>
  );
}

export function Surface({ style, ...props }: ViewProps) {
  const { colors } = useSignalTheme();
  return <View {...props} style={[styles.surface, { backgroundColor: colors.surface, borderColor: colors.border }, style]} />;
}

type ButtonProps = PressableProps & {
  label: string;
  kind?: 'primary' | 'secondary' | 'ghost' | 'danger';
  busy?: boolean;
  compact?: boolean;
};

export function Button({ label, kind = 'primary', busy, compact, disabled, style, ...props }: ButtonProps) {
  const { colors } = useSignalTheme();
  const bg = kind === 'primary' ? colors.accent : kind === 'danger' ? colors.negative : kind === 'secondary' ? colors.surfaceMuted : 'transparent';
  const fg = kind === 'primary' || kind === 'danger' ? colors.textInverse : kind === 'ghost' ? colors.accent : colors.text;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      disabled={disabled || busy}
      {...props}
      style={(state) => [
        styles.button,
        compact && styles.buttonCompact,
        { backgroundColor: bg, borderColor: kind === 'ghost' ? colors.border : bg, opacity: disabled ? 0.45 : state.pressed ? 0.78 : 1 },
        typeof style === 'function' ? style(state) : style,
      ]}
    >
      {busy ? <ActivityIndicator color={fg} /> : <AppText style={{ color: fg, fontWeight: '700' }}>{label}</AppText>}
    </Pressable>
  );
}

export function Chip({ label, selected = false, onPress, tone = 'default' }: { label: string; selected?: boolean; onPress?: () => void; tone?: 'default' | 'positive' | 'negative' | 'warning' }) {
  const { colors } = useSignalTheme();
  const toneColor = tone === 'positive' ? colors.positive : tone === 'negative' ? colors.negative : tone === 'warning' ? colors.warning : colors.textMuted;
  const content = <AppText variant="caption" style={{ color: selected ? colors.accent : toneColor, fontWeight: '700' }}>{label}</AppText>;
  const chipStyle = [styles.chip, { backgroundColor: selected ? colors.accentSoft : colors.surfaceMuted, borderColor: selected ? colors.accent : colors.border }];
  if (!onPress) {
    return <View style={chipStyle}>{content}</View>;
  }
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected }}
      onPress={onPress}
      style={({ pressed }) => [...chipStyle, { opacity: pressed ? 0.75 : 1 }]}
    >
      {content}
    </Pressable>
  );
}

export function SectionTitle({ title, action, onAction }: { title: string; action?: string; onAction?: () => void }) {
  return (
    <View style={styles.sectionTitle}>
      <AppText variant="subtitle">{title}</AppText>
      {action ? <Pressable accessibilityRole="button" onPress={onAction}><AppText tone="accent" variant="bodyStrong">{action}</AppText></Pressable> : null}
    </View>
  );
}

export function Divider() {
  const { colors } = useSignalTheme();
  return <View style={[styles.divider, { backgroundColor: colors.border }]} />;
}

export function ListRow({ title, subtitle, value, onPress, leading, accessibilityLabel }: { title: string; subtitle?: string; value?: string; onPress?: () => void; leading?: React.ReactNode; accessibilityLabel?: string }) {
  return (
    <Pressable
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel={accessibilityLabel ?? title}
      onPress={onPress}
      disabled={!onPress}
      style={({ pressed }) => [styles.listRow, { opacity: pressed ? 0.65 : 1 }]}
    >
      {leading}
      <View style={{ flex: 1, gap: 3 }}>
        <AppText variant="bodyStrong">{title}</AppText>
        {subtitle ? <AppText variant="caption" tone="muted">{subtitle}</AppText> : null}
      </View>
      {value ? <AppText tone="muted">{value}</AppText> : null}
      {onPress ? <AppText tone="muted">›</AppText> : null}
    </Pressable>
  );
}

export function Metric({ label, value, tone = 'default', helper }: { label: string; value: string; tone?: AppTextProps['tone']; helper?: string }) {
  return (
    <View style={{ flex: 1, gap: spacing.xs, minWidth: 96 }}>
      <AppText variant="caption" tone="muted">{label}</AppText>
      <AppText variant="subtitle" tone={tone} style={styles.tabular}>{value}</AppText>
      {helper ? <AppText variant="caption" tone="muted">{helper}</AppText> : null}
    </View>
  );
}

export function Banner({ title, body, tone = 'warning', action, onAction }: { title: string; body?: string; tone?: 'warning' | 'accent' | 'negative' | 'positive'; action?: string; onAction?: () => void }) {
  const { colors } = useSignalTheme();
  const bg = tone === 'warning' ? colors.warningSoft : tone === 'negative' ? colors.negativeSoft : tone === 'positive' ? colors.positiveSoft : colors.accentSoft;
  const fg = tone === 'warning' ? colors.warning : tone === 'negative' ? colors.negative : tone === 'positive' ? colors.positive : colors.accent;
  return (
    <View accessibilityRole="alert" style={[styles.banner, { backgroundColor: bg }]}>
      <View style={{ flex: 1, gap: 4 }}>
        <AppText variant="bodyStrong" style={{ color: fg }}>{title}</AppText>
        {body ? <AppText variant="caption" style={{ color: fg }}>{body}</AppText> : null}
      </View>
      {action ? <Pressable accessibilityRole="button" onPress={onAction}><AppText variant="bodyStrong" style={{ color: fg }}>{action}</AppText></Pressable> : null}
    </View>
  );
}

export function EmptyState({ title, body, action, onAction, symbol = '○' }: { title: string; body: string; action?: string; onAction?: () => void; symbol?: string }) {
  return (
    <View style={styles.state}>
      <AppText variant="hero" tone="muted">{symbol}</AppText>
      <AppText variant="subtitle">{title}</AppText>
      <AppText tone="muted" style={{ textAlign: 'center', lineHeight: 22 }}>{body}</AppText>
      {action ? <Button label={action} onPress={onAction} compact /> : null}
    </View>
  );
}

export function LoadingState({ label = '데이터 불러오는 중' }: { label?: string }) {
  const { colors } = useSignalTheme();
  return <View style={styles.state}><ActivityIndicator color={colors.accent} /><AppText tone="muted">{label}</AppText></View>;
}

export function ErrorState({ onRetry }: { onRetry?: () => void }) {
  const actionProps = onRetry ? { action: '다시 시도', onAction: onRetry } : {};
  return <EmptyState symbol="!" title="데이터를 불러오지 못했어요" body="연결을 확인한 뒤 다시 시도해 주세요." {...actionProps} />;
}

export function Field({ label, error, ...props }: TextInputProps & { label: string; error?: string }) {
  const { colors } = useSignalTheme();
  return (
    <View style={{ gap: spacing.xs }}>
      <AppText variant="caption" tone="muted">{label}</AppText>
      <TextInput
        accessibilityLabel={label}
        placeholderTextColor={colors.textMuted}
        {...props}
        style={[styles.input, { backgroundColor: colors.surface, color: colors.text, borderColor: error ? colors.negative : colors.border }, props.style]}
      />
      {error ? <AppText variant="caption" tone="negative">{error}</AppText> : null}
    </View>
  );
}

export function ToggleRow({ title, body, value, onValueChange, disabled }: { title: string; body?: string; value: boolean; onValueChange: (value: boolean) => void; disabled?: boolean }) {
  const { colors } = useSignalTheme();
  return (
    <View style={styles.toggleRow}>
      <View style={{ flex: 1, gap: 4 }}><AppText variant="bodyStrong">{title}</AppText>{body ? <AppText variant="caption" tone="muted">{body}</AppText> : null}</View>
      <Switch accessibilityLabel={title} value={value} onValueChange={onValueChange} disabled={disabled} trackColor={{ false: colors.border, true: colors.accent }} thumbColor={colors.surface} />
    </View>
  );
}

const styles = StyleSheet.create({
  text: { fontVariant: ['tabular-nums'], lineHeight: 21 },
  tabular: { fontVariant: ['tabular-nums'] },
  screen: { padding: spacing.md, paddingBottom: 80, gap: spacing.md },
  surface: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, padding: spacing.md },
  button: { minHeight: 52, borderRadius: radius.md, borderWidth: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: spacing.lg },
  buttonCompact: { minHeight: 40, borderRadius: radius.sm, alignSelf: 'flex-start' },
  chip: { minHeight: 34, borderRadius: radius.pill, borderWidth: 1, justifyContent: 'center', paddingHorizontal: spacing.sm },
  sectionTitle: { minHeight: 32, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: spacing.xs },
  divider: { height: StyleSheet.hairlineWidth },
  listRow: { minHeight: 64, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm },
  banner: { borderRadius: radius.sm, padding: spacing.sm, flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  state: { minHeight: 220, alignItems: 'center', justifyContent: 'center', gap: spacing.sm, padding: spacing.xl },
  input: { minHeight: 50, borderWidth: 1, borderRadius: radius.sm, paddingHorizontal: spacing.sm, fontSize: type.body },
  toggleRow: { minHeight: 70, flexDirection: 'row', alignItems: 'center', gap: spacing.md },
});

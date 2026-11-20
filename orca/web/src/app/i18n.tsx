import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import relativeTime from 'dayjs/plugin/relativeTime';
import localizedFormat from 'dayjs/plugin/localizedFormat';
import { en, zh, type MessageKey } from './messages';

dayjs.extend(relativeTime);
dayjs.extend(localizedFormat);

export type Lang = 'en' | 'zh';
const STORAGE_KEY = 'orca.lang';
const dictionaries: Record<Lang, Record<MessageKey, string>> = { en, zh };

export type TFunction = ((key: MessageKey, vars?: Record<string, string | number>) => string) & {
  /** Looks up a dynamic key such as `status.${value}`, falling back to {@code fallback}. */
  dyn: (key: string, fallback: string) => string;
};

interface I18nValue {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: TFunction;
}

const I18nContext = createContext<I18nValue | null>(null);

function initialLang(): Lang {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v === 'en' || v === 'zh') return v;
  } catch {
    /* storage unavailable */
  }
  return 'en';
}

export function format(template: string, vars?: Record<string, string | number>): string {
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m));
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang);

  useEffect(() => {
    dayjs.locale(lang === 'zh' ? 'zh-cn' : 'en');
    document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
  }, [lang]);

  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    try {
      localStorage.setItem(STORAGE_KEY, l);
    } catch {
      /* ignore */
    }
  }, []);

  const t = useMemo<TFunction>(() => {
    const dict = dictionaries[lang];
    const fn = ((key: MessageKey, vars?: Record<string, string | number>) =>
      format(dict[key] ?? en[key] ?? key, vars)) as TFunction;
    fn.dyn = (key: string, fallback: string) =>
      (dict as Record<string, string>)[key] ?? (en as Record<string, string>)[key] ?? fallback;
    return fn;
  }, [lang]);

  // dayjs.locale must be set before children format dates during this render
  dayjs.locale(lang === 'zh' ? 'zh-cn' : 'en');

  const value = useMemo(() => ({ lang, setLang, t }), [lang, setLang, t]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const v = useContext(I18nContext);
  if (!v) throw new Error('useI18n outside I18nProvider');
  return v;
}

export function useT(): TFunction {
  return useI18n().t;
}

import type { ChatMessage } from './types';

// 宏替换上下文。规格要求支持：{{char}} {{user}} {{persona}}
// {{description}} {{scenario}} {{original}}。这里一并保留 {{mes_example}}
// {{chat}} {{currentMessage}} {{lastMessage}} 等常用宏，多出的不影响兼容性。
export interface MacroContext {
  char?: string;
  user?: string;
  persona?: string;
  description?: string;
  scenario?: string;
  original?: string;
  mes_example?: string;
  chat?: string;
  currentMessage?: string;
  lastMessage?: string;
}

const MACRO_RE = /\{\{\s*([\w.]+)\s*\}\}/g;

export function applyMacros(text: string, ctx: MacroContext): string {
  return text.replace(MACRO_RE, (_full, name: string) => {
    const val = ctx[name as keyof MacroContext];
    if (val === undefined || val === null) return '';
    return String(val);
  });
}

export function applyMacrosToMessages(msgs: ChatMessage[], ctx: MacroContext): ChatMessage[] {
  return msgs.map((m) => ({ ...m, content: applyMacros(m.content, ctx) }));
}

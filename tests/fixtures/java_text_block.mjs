import { readFileSync } from 'node:fs';

/** Scripts in these Java text blocks use only uniform indentation and escaped backslashes. */
export function javaTextBlock(path, field) {
  const source = readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
  const start = `String ${field} = """\n`;
  const body = source.split(start)[1].split('""";')[0];
  return body.replace(/^ {12}/gm, '').replace(/\\\\/g, '\\');
}

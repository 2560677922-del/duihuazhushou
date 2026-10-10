/**
 * 把文本存成文件（TXT）。
 *
 * 为什么抽成一个函数：
 *   之前只有小说阅读器里导出 TXT 时写过一遍这段（Blob + a.click + revoke），
 *   现在冒险对话、小说编辑器也要导出，再抄两遍就是三份同样的代码 ——
 *   以后要改（比如加个文件名清洗）就得改三处，迟早不一致。
 */

/**
 * @param text 文件内容
 * @param filename 文件名（不含扩展名会自动补 .txt；非法字符会被替换）
 */
export function downloadTxt(text: string, filename: string): void {
  const safe = (filename || '导出')
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 80);
  const name = safe.toLowerCase().endsWith('.txt') ? safe : `${safe}.txt`;

  /*
   * \uFEFF 是 BOM。加了它，Windows 记事本才会把文件认成 UTF-8；
   * 不加就满屏乱码（中文用户的常见投诉）。
   */
  const blob = new Blob(['\uFEFF' + text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  /* 立刻 revoke 有些浏览器会来不及取到文件，延后释放 */
  setTimeout(() => URL.revokeObjectURL(url), 4000);
}

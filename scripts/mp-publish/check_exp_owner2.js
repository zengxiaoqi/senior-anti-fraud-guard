(() => {
  // 通用版：找出带「体验版」标记的那个版本号（取版本号文本与 tag 的 y 坐标判断归属）
  const tags = Array.from(document.querySelectorAll('span.status_tag'))
    .filter((s) => s.offsetParent !== null && /体验版/.test(s.textContent || ''))
    .map((s) => {
      const r = s.getBoundingClientRect();
      return { x: Math.round(r.x), y: Math.round(r.y) };
    });
  const vers = [];
  const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let n;
  while ((n = walk.nextNode())) {
    const t = (n.textContent || '').trim();
    if (!/^\d+\.\d+\.\d+$/.test(t)) continue;
    const e = n.parentElement;
    if (!e || !e.offsetParent) continue;
    const r = e.getBoundingClientRect();
    vers.push({ v: t, x: Math.round(r.x), y: Math.round(r.y) });
  }
  return {
    versions: vers,
    expTags: tags,
    // 与体验版 tag 纵向最接近的版本号即为当前体验版
    current: (() => {
      if (!tags.length || !vers.length) return null;
      let best = null;
      tags.forEach((t) => {
        vers.forEach((v) => {
          const dy = Math.abs(v.y - t.y);
          const dx = Math.abs(v.x - t.x);
          if (dy < 60 && dx < 60 && (!best || dy < best.dy)) best = { v: v.v, dy };
        });
      });
      return best ? best.v : null;
    })(),
  };
})()

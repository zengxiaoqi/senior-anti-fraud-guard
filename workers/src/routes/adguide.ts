/**
 * /api/adguide 路由（对齐本地 routes/adguide.js）。
 *
 * 广告「源头关闭」指引数据：蒸馏自 android-ad-cleaner（MIT）docs/清单 的
 * 品牌 → 入口路径 → 关哪些开关知识库。公开只读、无数据归属，不挂鉴权。
 * 数据文件 routes/adguide-data.json 与 workers/src/routes/adguide-data.json
 * 必须逐字节一致（契约测试比对）。
 */
import { Hono } from 'hono';
import type { AppEnv } from '../middleware/auth';
import adguideData from './adguide-data.json';

export const adguideRoutes = new Hono<AppEnv>();

/** 列表页只暴露 id/name/os，sections 详情走 :id 接口，响应体小 */
function brandBrief(b: (typeof adguideData.brands)[number]) {
  return { id: b.id, name: b.name, os: b.os };
}

adguideRoutes.get('/brands', (c) => {
  return c.json({
    success: true,
    data: {
      version: adguideData.version,
      source: adguideData.source,
      brands: adguideData.brands.map(brandBrief),
      universal: adguideData.universal
    }
  });
});

adguideRoutes.get('/brands/:id', (c) => {
  const brand = adguideData.brands.find((b) => b.id === c.req.param('id'));
  if (!brand) {
    return c.json({ success: false, error: '未知品牌' }, 404);
  }
  return c.json({ success: true, data: brand });
});

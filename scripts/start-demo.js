/**
 * 以演示环境启动服务：DB_PATH 指向演示库（纯假数据），不存在则先自动生成
 *
 * 用法: npm run start:demo
 */
const path = require('path');
const fs = require('fs');

const seed = require('./seed');

const DEMO_DB = process.env.DB_PATH || seed.DEFAULT_OUT;

(async () => {
  if (!fs.existsSync(DEMO_DB)) {
    console.log('演示库不存在，正在生成...');
    await seed.main(DEMO_DB);
  }
  process.env.DB_PATH = DEMO_DB;
  // 数据库就绪后再启动服务（server.js 内部会打印监听端口）
  require('../server.js');
})();

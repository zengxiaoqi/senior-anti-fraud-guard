// WGS-84 <-> GCJ-02 纠偏（与 Android family/GeoConverter.kt 同一套算法）
// 背景：服务端与老人端 GPS 落库坐标统一是 WGS-84；微信 map 组件与
// wx.chooseLocation 返回的是 GCJ-02（火星坐标）。两端互转不做，轨迹点
// 会整体偏移 50~500 米，围栏半径判断也会跟着错。
const PI = Math.PI;
const A = 6378245.0;
const EE = 0.00669342162296594323;

function outOfChina(lat, lng) {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
}

function transformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
  ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
  ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
  return ret;
}

function transformLng(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
  ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
  ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
  return ret;
}

// WGS-84 -> GCJ-02（显示用：微信地图 markers/polyline/circles）
function wgs2gcj(wgsLat, wgsLng) {
  if (outOfChina(wgsLat, wgsLng)) return [wgsLat, wgsLng];
  let dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0);
  let dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0);
  const radLat = wgsLat / 180.0 * PI;
  let magic = Math.sin(radLat);
  magic = 1 - EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
  dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
  return [wgsLat + dLat, wgsLng + dLng];
}

// GCJ-02 -> WGS-84（入库用：wx.chooseLocation 选点后必须反纠偏再提交，
// 否则围栏中心与老人端 GPS 判定差 50~500 米）
function gcj2wgs(gcjLat, gcjLng) {
  // 两次迭代近似，残差 < 1 米，与 Android 端同策略
  let wgsLat = gcjLat;
  let wgsLng = gcjLng;
  for (let i = 0; i < 2; i++) {
    const tmp = wgs2gcj(wgsLat, wgsLng);
    wgsLat += gcjLat - tmp[0];
    wgsLng += gcjLng - tmp[1];
  }
  return [wgsLat, wgsLng];
}

module.exports = { wgs2gcj, gcj2wgs };

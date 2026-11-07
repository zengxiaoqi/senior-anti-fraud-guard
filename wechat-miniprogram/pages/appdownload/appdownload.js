// 「下载守护 App」页：给子女扫码/发链接用，方便在老人手机上装 APK。
//
// 二维码编码的是**不带 versionCode** 的固定下载地址：
//   https://guard.chataifree.eu.org/api/app-update/download
// 双后端对缺省 versionCode 一律返回 releases.latest（routes/appUpdate.js /
// workers/src/routes/appUpdate.ts），所以码**永久有效、永远指向最新版**，
// 不用随发版重新生成。
//
// 微信内限制说明（文案里已给用户讲清）：
//  - 小程序内 <image> 上的二维码无法长按识别（微信不给非官方码这能力），
//    所以提供「点二维码放大预览 → 保存到相册 → 微信扫一扫选相册」的路径；
//  - 用微信扫开链接会落在微信内置浏览器，APK 下载会被拦/体验差，
//    文案引导用**系统相机/浏览器**扫码，或复制链接发到电脑浏览器。
const DOWNLOAD_URL = 'https://guard.chataifree.eu.org/api/app-update/download';

const app = getApp();

Page({
  data: {
    qrPath: '/images/qr-apk-download.png',
    downloadUrl: DOWNLOAD_URL
  },

  // 点二维码 → 全屏预览，长按可保存到相册
  onQrTap: function () {
    wx.previewImage({
      urls: [this.data.qrPath],
      fail: () => {
        // previewImage 对本地路径基本不会失败，兜底提示即可
        wx.showToast({ title: '预览失败，请重试', icon: 'none' });
      }
    });
  },

  onCopyLink: function () {
    wx.setClipboardData({
      data: this.data.downloadUrl,
      success: () => {
        wx.showToast({ title: '链接已复制', icon: 'success' });
      }
    });
  },

  // 分享给微信好友/文件传输助手：对方在电脑微信打开链接即可直接下载 APK
  onShareAppMessage: function () {
    return {
      title: '守护防诈 App 下载（发给家人安装）',
      path: '/pages/appdownload/appdownload'
    };
  }
});

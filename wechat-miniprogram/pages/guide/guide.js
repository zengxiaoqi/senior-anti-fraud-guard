const app = getApp();

Page({
  data: {
    activeSection: 'tips',
    fraudTypes: [
      { name: '冒充公检法', desc: '自称警察/检察官，涉嫌洗钱，要求转账至安全账户', color: '#EF4444' },
      { name: '虚假投资理财', desc: '承诺高收益零风险，诱导下载APP或转账', color: '#F59E0B' },
      { name: '保健品骗局', desc: '免费体检/专家义诊，夸大疗效诱导购买高价药品', color: '#8B5CF6' },
      { name: '冒充熟人借钱', desc: '盗用子女/亲友头像手机号，紧急借钱', color: '#EC4899' },
      { name: '中奖/退税诈骗', desc: '通知中奖或退税，要求先交手续费/保证金', color: '#14B8A6' },
      { name: '网络贷款诈骗', desc: '低息贷款诱导，要求先交押金/流水费', color: '#F97316' }
    ],
    emergencyContacts: [
      { name: '报警电话', number: '110', icon: '🚔' },
      { name: '反诈专线', number: '96110', icon: '📞' },
      { name: '银行客服', number: '95588', icon: '🏦' },
      { name: '子女手机', number: '', icon: '👨' }
    ],
    guideSteps: [
      { step: 1, title: '保持冷静', desc: '遇到紧急情况先深呼吸，不要慌张，给子女打电话确认' },
      { step: 2, title: '多方核实', desc: '公检法不会电话办案，所有要求转账的都是诈骗' },
      { step: 3, title: '保护信息', desc: '不透露银行卡号、密码、短信验证码给任何人' },
      { step: 4, title: '及时报警', desc: '发现被骗立即拨打110，保存好转账凭证和聊天记录' }
    ]
  },

  onLoad: function () {
    this.loadElderPhone();
  },

  onShow: function () {
    this.loadElderPhone();
  },

  loadElderPhone: function () {
    const that = this;
    wx.request({
      url: `${app.globalData.serverHost}/api/auth/user/${app.globalData.elderId}`,
      success: (res) => {
        if (res.data.success && res.data.data.phone) {
          const contacts = that.data.emergencyContacts;
          contacts[3].number = res.data.data.phone;
          that.setData({ emergencyContacts: contacts });
        }
      }
    });
  },

  onSectionTap: function (e) {
    this.setData({ activeSection: e.currentTarget.dataset.section });
  },

  onCallTap: function (e) {
    const number = e.currentTarget.dataset.number;
    if (number) {
      wx.makePhoneCall({ phoneNumber: number });
    } else {
      wx.showToast({ title: '未绑定手机号', icon: 'none' });
    }
  },

  onCopyNumber: function (e) {
    const number = e.currentTarget.dataset.number;
    if (number) {
      wx.setClipboardData({
        data: number,
        success: () => wx.showToast({ title: '已复制', icon: 'success' })
      });
    }
  },

  onShareTap: function () {
    wx.showShareMenu({ withShareTicket: true });
  }
});

// 场景 2 · 痛点（120-390帧 / 4-13s）：六大诈骗套路快闪（文案来自产品《防护指南》真实清单）
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT, clampI, sceneExit, mkSpring, FPS } from "./shared";

const FRAUDS = [
  { tag: "骗局 01", title: "冒充公检法", line: "“您涉嫌洗钱，把钱转到安全账户”" },
  { tag: "骗局 02", title: "虚假投资理财", line: "“高收益零风险，稳赚不赔”" },
  { tag: "骗局 03", title: "保健品骗局", line: "“免费体检专家义诊，包治百病”" },
  { tag: "骗局 04", title: "冒充熟人借钱", line: "“我是你孙子，急事借点钱”" },
  { tag: "骗局 05", title: "中奖退税诈骗", line: "“先交手续费，保证金全额返”" },
  { tag: "骗局 06", title: "网络贷款诈骗", line: "“低息秒到账，先交押金”" },
];

export const Scene2Frauds: React.FC = () => {
  const frame = useCurrentFrame();
  const total = 270;
  const CARD = 40; // 每张 40 帧
  const freezeStart = CARD * 6; // 240

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      {/* 背景红色扫线 */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: `repeating-linear-gradient(0deg, transparent 0 90px, ${C.red}08 90px 92px)`,
        }}
      />

      {frame < freezeStart ? (
        <div style={{ position: "absolute", inset: 0 }}>
          {FRAUDS.map((f, i) => {
            const local = frame - i * CARD;
            if (local < 0 || local >= CARD) return null;
            const inS = mkSpring(local, FPS, 0, { damping: 200, stiffness: 380 });
            const out = clampI(local, CARD - 12, CARD, 1, 0);
            const x = (1 - inS) * 900;
            return (
              <div
                key={f.title}
                style={{
                  position: "absolute",
                  top: 560,
                  left: 80,
                  right: 80,
                  padding: "52px 56px",
                  borderRadius: 36,
                  background: `linear-gradient(135deg, ${C.bgCard} 0%, ${C.redDeep}44 130%)`,
                  border: `3px solid ${C.red}88`,
                  boxShadow: `0 24px 90px ${C.red}33`,
                  opacity: out,
                  transform: `translateX(${x}px) rotate(${(1 - inS) * 4}deg)`,
                }}
              >
                <div
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    marginBottom: 28,
                  }}
                >
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 20,
                      fontFamily: FONT,
                      fontSize: 30,
                      fontWeight: 800,
                      color: C.red,
                      letterSpacing: 3,
                    }}
                  >
                    <span style={{ fontSize: 44 }}>⚠</span>
                    {f.tag}
                  </div>
                  <div
                    style={{
                      width: 16,
                      height: 16,
                      borderRadius: 8,
                      background: C.red,
                      boxShadow: `0 0 24px ${C.red}`,
                    }}
                  />
                </div>
                <div
                  style={{
                    fontFamily: FONT,
                    fontSize: 76,
                    fontWeight: 900,
                    color: C.text,
                    lineHeight: 1.2,
                  }}
                >
                  {f.title}
                </div>
                <div
                  style={{
                    marginTop: 22,
                    fontFamily: FONT,
                    fontSize: 38,
                    fontWeight: 500,
                    color: "#FFB3C1",
                  }}
                >
                  {f.line}
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        // 定格收尾
        <div
          style={{
            position: "absolute",
            inset: 0,
            display: "flex",
            flexDirection: "column",
            justifyContent: "center",
            alignItems: "center",
            padding: "0 80px",
          }}
        >
          <div
            style={{
              fontFamily: FONT,
              fontSize: 40,
              fontWeight: 800,
              color: C.red,
              letterSpacing: 8,
              marginBottom: 36,
              opacity: clampI(frame, freezeStart + 4, freezeStart + 16, 0, 1),
            }}
          >
            骗子的剧本，早就写好了
          </div>
          <div
            style={{
              fontFamily: FONT,
              fontSize: 104,
              fontWeight: 900,
              color: C.text,
              textAlign: "center",
              lineHeight: 1.3,
              opacity: clampI(frame, freezeStart + 10, freezeStart + 24, 0, 1),
              transform: `scale(${clampI(frame, freezeStart + 10, freezeStart + 24, 1.15, 1)})`,
            }}
          >
            而他们
            <br />
            <span style={{ color: C.red }}>毫无防备</span>
          </div>
        </div>
      )}

      <AbsoluteFill
        style={{ opacity: sceneExit(frame, total, 12).opacity, pointerEvents: "none" }}
      />
    </AbsoluteFill>
  );
};

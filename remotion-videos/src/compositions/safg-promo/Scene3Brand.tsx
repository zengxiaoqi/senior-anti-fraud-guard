// 场景 3 · 品牌亮相（390-570帧 / 13-19s）：蓝光扫过 → 盾牌 → 产品名
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT, clampI, mkSpring, FPS, sceneExit } from "./shared";

export const Scene3Brand: React.FC = () => {
  const frame = useCurrentFrame();
  const total = 180;

  // 扫光（斜条横扫）
  const sweepX = clampI(frame, 6, 46, -700, 1800);

  // 盾牌弹簧
  const shieldS = mkSpring(frame, FPS, 34, { damping: 60, stiffness: 260 });

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      {/*  Radial 蓝光 */}
      <div
        style={{
          position: "absolute",
          top: 560,
          left: "50%",
          width: 1100,
          height: 1100,
          marginLeft: -550,
          borderRadius: 550,
          background: `radial-gradient(circle, ${C.blue}33 0%, transparent 60%)`,
          opacity: clampI(frame, 30, 70, 0, 1) * 0.9,
        }}
      />

      {/* 扫光斜条 */}
      <div
        style={{
          position: "absolute",
          top: -200,
          left: sweepX,
          width: 420,
          height: 2400,
          background: `linear-gradient(90deg, transparent, ${C.blue}CC, #fff, ${C.blue}CC, transparent)`,
          transform: "skewX(-18deg)",
          filter: "blur(2px)",
          opacity: clampI(frame, 6, 14, 0, 1) * clampI(frame, 40, 48, 1, 0),
        }}
      />

      {/* 盾牌 */}
      <div
        style={{
          position: "absolute",
          top: 470,
          left: 0,
          width: "100%",
          display: "flex",
          justifyContent: "center",
          transform: `scale(${shieldS})`,
          opacity: clampI(frame, 34, 48, 0, 1),
        }}
      >
        <svg width={300} height={330} viewBox="0 0 300 330">
          <defs>
            <linearGradient id="shieldG" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stopColor={C.blue} />
              <stop offset="100%" stopColor={C.purple} />
            </linearGradient>
          </defs>
          <path
            d="M150 20 L270 70 V170 C270 250 220 295 150 315 C80 295 30 250 30 170 V70 Z"
            fill="url(#shieldG)"
            stroke="#fff"
            strokeWidth={6}
          />
          <path
            d="M92 168 L136 212 L214 126"
            fill="none"
            stroke="#fff"
            strokeWidth={20}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </div>

      {/* 产品名 */}
      <div style={{ position: "absolute", top: 880, left: 0, width: "100%", textAlign: "center" }}>
        <div
          style={{
            fontFamily: FONT,
            fontSize: 118,
            fontWeight: 900,
            color: C.text,
            letterSpacing: 6,
            opacity: clampI(frame, 52, 70, 0, 1),
            transform: `translateY(${clampI(frame, 52, 70, 50, 0)}px)`,
          }}
        >
          长者防诈守护
        </div>
        <div
          style={{
            marginTop: 22,
            fontFamily: FONT,
            fontSize: 34,
            fontWeight: 700,
            color: C.blue,
            letterSpacing: 12,
            opacity: clampI(frame, 62, 78, 0, 1),
          }}
        >
          SENIOR ANTI-FRAUD GUARD
        </div>
        <div
          style={{
            marginTop: 44,
            fontFamily: FONT,
            fontSize: 42,
            fontWeight: 600,
            color: C.textDim,
            opacity: clampI(frame, 72, 88, 0, 1),
            transform: `translateY(${clampI(frame, 72, 88, 30, 0)}px)`,
          }}
        >
          装在爸妈手机里的 <span style={{ color: C.text, fontWeight: 800 }}>24 小时防线</span>
        </div>
      </div>

      {/* 三能力预告胶囊 */}
      <div
        style={{
          position: "absolute",
          top: 1420,
          left: 0,
          width: "100%",
          display: "flex",
          justifyContent: "center",
          gap: 28,
        }}
      >
        {[
          { t: "感知", c: C.blue },
          { t: "干预", c: C.red },
          { t: "存证", c: C.amber },
        ].map((p, i) => {
          const s = mkSpring(frame, FPS, 96 + i * 10, { damping: 60, stiffness: 300 });
          return (
            <div
              key={p.t}
              style={{
                padding: "18px 44px",
                borderRadius: 999,
                border: `3px solid ${p.c}`,
                background: `${p.c}1E`,
                fontFamily: FONT,
                fontSize: 44,
                fontWeight: 900,
                color: p.c,
                transform: `scale(${s})`,
                opacity: clampI(frame, 96 + i * 10, 108 + i * 10, 0, 1),
              }}
            >
              {p.t}
            </div>
          );
        })}
      </div>

      <AbsoluteFill
        style={{ opacity: sceneExit(frame, total, 12).opacity, pointerEvents: "none" }}
      />
    </AbsoluteFill>
  );
};

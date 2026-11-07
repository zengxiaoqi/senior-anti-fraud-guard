// 场景 1 · Hook（0-120帧 / 0-4s）：红色警报 + 打字机大字
import { AbsoluteFill, useCurrentFrame } from "remotion";
import {
  C,
  FONT,
  AlertLamps,
  GlitchText,
  HeartbeatLine,
  clampI,
  pulse,
  sceneExit,
  typeText,
} from "./shared";

export const Scene1Hook: React.FC = () => {
  const frame = useCurrentFrame();
  const total = 120;

  // 打字机文案（核心钩子，三行排版防溢出：容器可用宽 940px，116px 字号一行最多 8 字）
  const line1 = "今晚，";
  const line2 = "骗子就会";
  const line3 = "打给你爸妈。";
  const t1 = typeText(frame, line1, 14, 3);
  const t2 = typeText(frame, line2, 14 + line1.length * 3, 3);
  const t3 = typeText(frame, line3, 14 + (line1.length + line2.length) * 3, 3);
  const typingDone =
    14 + (line1.length + line2.length + line3.length) * 3;

  // 警报胶囊闪烁
  const badgeP = pulse(frame, 18);

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      <AlertLamps frame={frame} color={C.red} />

      {/* 顶部警报胶囊 */}
      <div
        style={{
          position: "absolute",
          top: 210,
          left: 0,
          width: "100%",
          display: "flex",
          justifyContent: "center",
          opacity: clampI(frame, 4, 16, 0, 1),
          transform: `scale(${clampI(frame, 4, 16, 0.7, 1)})`,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 16,
            padding: "14px 34px",
            borderRadius: 999,
            background: C.red,
            boxShadow: `0 0 ${20 + badgeP * 40}px ${C.red}88`,
            fontFamily: FONT,
            fontSize: 32,
            fontWeight: 900,
            color: "#fff",
            letterSpacing: 6,
          }}
        >
          <span style={{ fontSize: 36 }}>⚠</span> 高危预警
        </div>
      </div>

      {/* 中央大字 */}
      <div
        style={{
          position: "absolute",
          top: 620,
          left: 0,
          width: "100%",
          padding: "0 70px",
          textAlign: "left",
        }}
      >
        <div style={{ fontSize: 116, fontWeight: 900, lineHeight: 1.28 }}>
          <GlitchText text={t1} size={116} frame={frame} />
          <br />
          <GlitchText text={t2} size={116} color={C.red} frame={frame} />
          <br />
          <GlitchText text={t3} size={116} frame={frame} />
          {/* 光标：跟在最后一行后 */}
          <span
            style={{
              display: "inline-block",
              width: 10,
              height: 96,
              marginLeft: 14,
              background: C.red,
              verticalAlign: "-8px",
              opacity:
                frame < typingDone
                  ? frame % 16 < 8
                    ? 1
                    : 0
                  : 0.4 + pulse(frame, 20) * 0.6,
            }}
          />
        </div>
      </div>

      {/* 底部三骗局标签 */}
      <div
        style={{
          position: "absolute",
          top: 1130,
          left: 70,
          display: "flex",
          gap: 20,
          opacity: clampI(frame, typingDone + 8, typingDone + 24, 0, 1),
          transform: `translateY(${clampI(frame, typingDone + 8, typingDone + 24, 30, 0)}px)`,
        }}
      >
        {["电信诈骗", "保健品会销", "养老投资"].map((t, i) => (
          <div
            key={t}
            style={{
              padding: "12px 26px",
              borderRadius: 12,
              border: `2px solid ${C.red}66`,
              background: `${C.red}14`,
              color: C.text,
              fontFamily: FONT,
              fontSize: 30,
              fontWeight: 700,
              opacity: clampI(frame, typingDone + 10 + i * 6, typingDone + 22 + i * 6, 0, 1),
            }}
          >
            {t}
          </div>
        ))}
      </div>

      <div
        style={{
          position: "absolute",
          top: 1215,
          left: 70,
          fontFamily: FONT,
          fontSize: 34,
          fontWeight: 500,
          color: C.textDim,
          opacity: clampI(frame, typingDone + 14, typingDone + 30, 0, 1),
        }}
      >
        总有一款，正盯上你家老人
      </div>

      <HeartbeatLine color={C.red} top={1740} />

      {/* 场景退出 */}
      <AbsoluteFill style={{ opacity: sceneExit(frame, total, 12).opacity, pointerEvents: "none" }} />
    </AbsoluteFill>
  );
};

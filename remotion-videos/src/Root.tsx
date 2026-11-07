import React from "react";
import { Composition } from "remotion";
import { SafgPromoComposition } from "./compositions/safg-promo/VideoComposition";

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="safg-promo"
        component={SafgPromoComposition}
        durationInFrames={1560}
        fps={30}
        width={1080}
        height={1920}
      />
    </>
  );
};

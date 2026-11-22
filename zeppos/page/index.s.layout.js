import * as hmUI from "@zos/ui";
import { px } from "@zos/utils";
import { DEVICE_WIDTH } from "../utils/config/device";

export const VALUE_TEXT = {
  x: px(20),
  y: px(100),
  w: DEVICE_WIDTH - 2 * px(20),
  h: px(110),
  color: 0xffffff,
  text_size: px(64),
  align_h: hmUI.align.CENTER_H,
  align_v: hmUI.align.CENTER_V,
};

// Seta em imagem de 64×64 (assets/<alvo>/arrow/*.png), centrada entre o valor e a idade.
export const ARROW_IMG = {
  x: (DEVICE_WIDTH - 64) / 2,
  y: px(215),
  w: 64,
  h: 64,
};

export const AGE_TEXT = {
  x: px(20),
  y: px(300),
  w: DEVICE_WIDTH - 2 * px(20),
  h: px(56),
  color: 0xb0b0b0,
  text_size: px(30),
  align_h: hmUI.align.CENTER_H,
  align_v: hmUI.align.CENTER_V,
};

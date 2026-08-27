import {stores} from "@/base/stores";
import {env} from "@/base/ts/utils/env.ts";

export const initStyleTheme = () => {
  const linkId = 'amis-theme-link';

  // amis的主题色更新
  const styleLink = document.getElementById(linkId);
  if (styleLink) {
    styleLink.remove();
  }

  const newStyleLink = document.createElement('link');
  newStyleLink.id = linkId;
  newStyleLink.rel = 'stylesheet';
  newStyleLink.href = `${env('api_prefix')}/sys/styleTheme/public/${stores().theme.type}.css`;
  document.body.appendChild(newStyleLink);
}
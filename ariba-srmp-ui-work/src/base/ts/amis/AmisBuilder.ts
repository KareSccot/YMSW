import amisEnv from "@/base/ts/amis/AmisEnv.ts";
import {stores} from "@/base/stores";
import {getI18nPack} from "@/base/ts/service/I18nService.ts";
// @ts-ignore
let amis = amisRequire('amis/embed');

export const buildAmis = (amis_root_id: string, schema: any, props?: any, replaceText?: any) => {
  const hash = window.location.hash;
  const queryIndex = hash.indexOf('?');
  const query: Record<string, string> = {};
  let search = '';
  if (queryIndex !== -1) {
    search = hash.substring(queryIndex);
    new URLSearchParams(hash.substring(queryIndex + 1)).forEach((v, k) => query[k] = v);
  }
  
  console.log('AmisBuilder location:', { search, query, hash: window.location.hash });
  
  return amis.embed(
    '#'+amis_root_id,
    schema,
    {
      location: {
        ...history.location,
        search: search,
        query: query,
      },
      data: {
        ...query,
      },
      context: {
      },
      locale: stores().cache.lang,
      ...props,
    },
    {
      ...amisEnv,
      theme: 'cxd',
      replaceText: {
        ...getI18nPack(),
        ...replaceText,
      }
    }
  );
}

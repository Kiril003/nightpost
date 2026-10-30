/// <reference types="vite/client" />

/**
 * Комерційні адреси приходять зі збірки, а не з коду: коли крамницю заведено,
 * рівень оживає однією змінною. Усі поля необов'язкові — сайт мусить лишатись
 * цілим і коли жодної з них немає.
 */
interface ImportMetaEnv {
  readonly VITE_CHECKOUT_PERSONAL?: string;
  readonly VITE_CHECKOUT_CREW?: string;
  readonly VITE_CHECKOUT_UNIT?: string;
  readonly VITE_LICENSE_SERVER?: string;
  readonly VITE_BUILDER_SERVER?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

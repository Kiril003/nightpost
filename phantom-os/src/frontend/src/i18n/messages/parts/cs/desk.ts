import type { MessageKey } from '../../uk';

export const cs: Record<Extract<MessageKey, `deskPane.${string}`>, string> = {
  "deskPane.day": "Den",
  "deskPane.map": "Mapa",
  "deskPane.wallet": "Peněženka",
  "deskPane.info": "Info",
  "deskPane.close": "Zavřít panel",
  "deskPane.tabsAria": "Boční panel",
  "deskPane.emptyDay": "Na dnes nic není",
  "deskPane.emptyWallet": "Vyberte chat",
  "deskPane.emptyInfo": "Vyberte chat",
  "deskPane.devnet": "Solana Devnet",
  "deskPane.mainnet": "Solana Mainnet",
  "deskPane.noPay": "Převod není dostupný",
};

import React, { useState, useCallback, useMemo, useRef, useEffect } from 'react';
import { Search, X, Clock, Smile, Heart, ThumbsUp, Dog, Coffee, Gamepad2, Plane, Lightbulb, Sparkles } from 'lucide-react';
import { soundFx } from '../../utils/messengerSound';
import { FormSheet } from './FormSheet';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import { ReactionGlyph } from './glyphs/ReactionGlyph';
import { GLYPHS } from './glyphs/reactionGlyphs';

interface ReactionPickerModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectEmoji: (emoji: string) => void;
  position?: { x: number; y: number };
}

const RECENT_KEY = 'phantom_recent_reactions';
const MAX_RECENT = 24;
/** Барви конфеті — токени: бурштин, два тони людей і success. */
const CONFETTI_TOKENS = ['--ph-accent', '--ph-person-1', '--ph-person-3', '--ph-success'];

function loadRecent(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}
function saveRecent(emojis: string[]) {
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(emojis.slice(0, MAX_RECENT)));
  } catch { /* quota */ }
}

export const emojiCategories = [
  {
    id: 'recent',
    name: 'reactionPickerModal.catRecent' as MessageKey,
    icon: Clock,
    emojis: [] as string[],
  },
  {
    id: 'nightpost',
    name: 'reactionPickerModal.catNightpost' as MessageKey,
    icon: Sparkles,
    emojis: GLYPHS.map((g) => g.emoji),
  },
  {
    id: 'smileys',
    name: 'reactionPickerModal.catSmileys' as MessageKey,
    icon: Smile,
    emojis: [
      '😀', '😃', '😄', '😁', '😆', '😅', '🤣', '😂', '🙂', '😉',
      '😊', '😇', '🥰', '😍', '🤩', '😘', '😗', '😚', '😋', '😛',
      '😜', '🤪', '😝', '🤑', '🤗', '🤭', '🤫', '🤔', '🫡', '🤐',
      '🤨', '😐', '😑', '😶', '😏', '😒', '🙄', '😬', '😮‍💨', '🤥',
      '😌', '😔', '😪', '🤤', '😴', '😷', '🤒', '🤕', '🤧', '🥵',
      '🥶', '🥴', '😵', '🤯', '🤠', '🥳', '🥸', '😎', '🤓', '🧐',
    ],
  },
  {
    id: 'gestures',
    name: 'reactionPickerModal.catGestures' as MessageKey,
    icon: ThumbsUp,
    emojis: [
      '👍', '👎', '👊', '✊', '🤛', '🤜', '👏', '🙌', '🫶', '🤝',
      '🤲', '👐', '✋', '🤚', '🖐️', '☝️', '👆', '👇', '👈', '👉',
      '🫰', '🤌', '🤏', '✌️', '🤞', '🫰', '🤟', '🤘', '🤙', '💪',
      '🫵', '👋', '🖖', '🫸', '🫷', '🤟', '🙏', '🫶', '🤲', '👀',
    ],
  },
  {
    id: 'hearts',
    name: 'reactionPickerModal.catHearts' as MessageKey,
    icon: Heart,
    emojis: [
      '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '🤍', '🤎', '💔',
      '❤️‍🔥', '❤️‍🩹', '❣️', '💕', '💞', '💓', '💗', '💖', '💝', '💘',
      '💟', '♥️', '🫀', '💋', '😍', '🥰', '😘', '😻', '💑', '💏',
    ],
  },
  {
    id: 'animals',
    name: 'reactionPickerModal.catAnimals' as MessageKey,
    icon: Dog,
    emojis: [
      '🐶', '🐱', '🐭', '🐹', '🐰', '🦊', '🐻', '🐼', '🐻‍❄️', '🐨',
      '🐯', '🦁', '🐮', '🐷', '🐸', '🐵', '🙈', '🙉', '🙊', '🐔',
      '🐧', '🐦', '🦅', '🦆', '🦉', '🐺', '🐗', '🐴', '🦄', '🐝',
      '🐛', '🦋', '🐌', '🐞', '🐜', '🦟', '🐍', '🐢', '🦎', '🐙',
    ],
  },
  {
    id: 'food',
    name: 'reactionPickerModal.catFood' as MessageKey,
    icon: Coffee,
    emojis: [
      '🍎', '🍐', '🍊', '🍋', '🍌', '🍉', '🍇', '🍓', '🫐', '🍈',
      '🍒', '🍑', '🥭', '🍍', '🥥', '🥝', '🍅', '🥑', '🥦', '🌽',
      '🌶️', '🫑', '🥒', '🧄', '🧅', '🍄', '🥜', '🫘', '🍞', '🥐',
      '🥖', '🧀', '🍕', '🌭', '🍔', '🍟', '🌮', '🍣', '🍰', '☕',
    ],
  },
  {
    id: 'activities',
    name: 'reactionPickerModal.catActivities' as MessageKey,
    icon: Gamepad2,
    emojis: [
      '⚽', '🏀', '🏈', '⚾', '🥎', '🎾', '🏐', '🏉', '🥏', '🎱',
      '🏓', '🏸', '🏒', '🥊', '🎿', '⛷️', '🏂', '🪂', '🏋️', '🤸',
      '🎮', '🕹️', '🎲', '🧩', '♟️', '🎯', '🎳', '🎪', '🎨', '🎭',
      '🎤', '🎧', '🎵', '🎶', '🎹', '🥁', '🎸', '🎺', '🎻', '🪗',
    ],
  },
  {
    id: 'travel',
    name: 'reactionPickerModal.catTravel' as MessageKey,
    icon: Plane,
    emojis: [
      '🚗', '🚕', '🚌', '🏎️', '🚑', '🚒', '🚐', '🛻', '🚚', '🚂',
      '🚆', '🚇', '🚊', '✈️', '🛫', '🛬', '🚀', '🛸', '🚁', '⛵',
      '🚢', '⛽', '🚦', '🏗️', '🏠', '🏢', '🏰', '🗼', '🗽', '⛪',
      '🕌', '🛕', '⛩️', '🏖️', '🏔️', '🌋', '🗻', '🏕️', '🌅', '🌄',
    ],
  },
  {
    id: 'symbols',
    name: 'reactionPickerModal.catSymbols' as MessageKey,
    icon: Lightbulb,
    emojis: [
      '💡', '🔥', '✨', '⭐', '🌟', '💫', '⚡', '🌈', '☀️', '🌙',
      '⛅', '❄️', '💧', '🌊', '🎉', '🎊', '🎁', '🏆', '🥇', '🎖️',
      '📌', '📈', '📉', '💻', '🛠️', '⏳', '💎', '📑', '🤝', '🚀',
      '✅', '❌', '⚠️', '🔔', '💬', '💭', '🗯️', '♻️', '🔑', '🪄',
    ],
  },
];

/**
 * Слова, якими емодзі шукають. Українською й англійською — бо розкладка
 * буває не та, а шукати з перемиканням розкладки ніхто не буде.
 *
 * Покриття стереже `reactionPickerSearch.test.ts`: кожне емодзі з
 * [emojiCategories] мусить мати рядок тут, інакше воно ненаходиме.
 */
export const EMOJI_WORDS: Record<string, string> = {
  '🙋': 'я в ділі підняти руку in hand raised', '🤷': 'можливо не знаю maybe shrug', '🚫': 'не можу заборона cant no',
  '⏱️': 'маю 25 хв таймер minutes timer', '🚶': 'вже йду on my way walk', '💸': 'сплачено гроші paid money',
  '📍': 'я тут місце here pin', '😮': 'ого здивування wow surprised', '😢': 'сумно плач sad cry',
  '👌': 'добре ok', '🙇': 'вибач sorry bow',
  // === Смайлики ===
  '😀': 'усмішка радість grin happy', '😃': 'усмішка очі smile eyes happy',
  '😄': 'сміх усмішка laugh smile', '😁': 'усмішка зуби grin teeth',
  '😆': 'сміх закриті squint laugh', '😅': 'піт нервово sweat smile awkward',
  '🤣': 'ржу лол rofl lol rolling', '😂': 'смішно сміх слізи laugh lol funny tears',
  '🙂': 'легка усмішка slight smile', '😉': 'підморгнути wink',
  '😊': 'усмішка радість посмішка smile happy blush', '😇': 'ангел святий angel halo innocent',
  '🥰': 'закоханий серця love hearts adore', '😍': 'закоханий очі серця love eyes adore heart',
  '🤩': 'зірки очі захват star struck wow', '😘': 'поцілунок kiss blow',
  '😗': 'поцілунок kiss', '😚': 'поцілунок kiss closed',
  '😋': 'смачно язик yummy delicious', '😛': 'язик tongue playful',
  '😜': 'підморгнути язик wink tongue crazy', '🤪': 'шалений crazy zany wild',
  '😝': 'язик закриті squint tongue', '🤑': 'гроші долар money rich',
  '🤗': 'обійми hug hugging', '🤭': 'ой тихенько oops giggle shy',
  '🤫': 'тихо секрет shh quiet secret', '🤔': 'думаю сумнів питання think hmm wondering',
  '🫡': 'честь салют так точно salute yes sir', '🤐': 'замок рот zip mouth shut',
  '🤨': 'підозра bruh skeptic raised brow', '😐': 'нейтральний neutral blank',
  '😑': 'нудно expressionless bored', '😶': 'мовчки speechless no mouth',
  '😏': 'хитрий smirk sly', '😒': 'роздратований unamused annoyed',
  '🙄': 'закочувати очі eye roll whatever', '😬': 'ніяково grimace awkward cringe',
  '😮‍💨': 'зітхання exhale sigh relief', '🤥': 'брехун pinocchio liar',
  '😌': 'спокій relieved peaceful calm', '😔': 'сумно pensive sad thoughtful',
  '😪': 'сонний sleepy tired', '🤤': 'слюні drool hungry',
  '😴': 'сплю сон нудно sleep sleepy bored zzz', '😷': 'маска хворий mask sick',
  '🤒': 'температура хворий thermometer sick fever', '🤕': 'бинт поранений bandage hurt',
  '🤧': 'апчхі чхання sneeze tissue', '🥵': 'гаряче hot heat sweating',
  '🥶': 'холодно freezing cold ice', '🥴': 'п\'яний woozy dizzy drunk',
  '😵': 'запаморочення dizzy dazed spiral', '🤯': 'вибух мозку шок mind blown shock explode',
  '🤠': 'ковбой cowboy hat yeehaw', '🥳': 'святкує вітання ура celebrate party birthday',
  '🥸': 'маскування disguise glasses incognito', '😎': 'окуляри крутий cool sunglasses',
  '🤓': 'ботанік nerd glasses smart', '🧐': 'монокль inspect monocle curious',
  // === Жести ===
  '👍': 'палець вгору лайк добре thumbs up like ok yes', '👎': 'палець вниз не подоба thumbs down dislike',
  '👊': 'кулак fist bump punch', '✊': 'кулак стиснутий raised fist power',
  '🤛': 'кулак лівий left fist', '🤜': 'кулак правий right fist',
  '👏': 'оплески аплодисменти браво clap applause bravo', '🙌': 'руки вгору так hands raised yay celebrate',
  '🫶': 'серце руки heart hands love', '🤝': 'рукостискання згода домовились handshake deal agree',
  '🤲': 'долоні palms up open', '👐': 'відкриті руки open hands jazz',
  '✋': 'стоп рука stop hand halt', '🤚': 'підняти руку raised back',
  '🖐️': 'п\'ять пальців five fingers hand', '☝️': 'вгору палець point up one',
  '👆': 'палець вгору point up', '👇': 'палець вниз point down',
  '👈': 'палець ліворуч point left', '👉': 'палець праворуч point right',
  '🫰': 'клацання snap fingers click money', '🤌': 'італія щіпка pinch italian perfection',
  '🤏': 'трішки маленько small little pinch', '✌️': 'перемога мир victory peace two',
  '🤞': 'на удачу crossed fingers luck hope', '🤟': 'рок love you rock horns',
  '🤘': 'рок метал rock metal horns', '🤙': 'зателефонуй call me shaka hang loose',
  '💪': 'сила м\'язи strong muscle flex bicep power', '🫵': 'ти you pointing',
  '👋': 'привіт хай wave hello hi bye', '🖖': 'вулкан spock trek vulcan',
  '🫸': 'штовхнути push palm', '🫷': 'ліво push left palm',
  '🙏': 'благаю дякую прошу pray please thank namaste', '👀': 'очі дивлюсь бачу eyes look watch see',
  // === Серця ===
  '❤️': 'серце любов кохання heart love red', '🧡': 'серце оранжеве orange heart',
  '💛': 'серце жовте yellow heart', '💚': 'серце зелене green heart',
  '💙': 'серце синє blue heart', '💜': 'серце фіолетове purple heart',
  '🖤': 'серце чорне black heart dark', '🤍': 'серце біле white heart pure',
  '🤎': 'серце коричневе brown heart', '💔': 'розбите серце broken heart sad',
  '❤️‍🔥': 'серце вогонь heart fire passion', '❤️‍🩹': 'серце пластир mending heart heal',
  '❣️': 'серце оклик exclamation heart', '💕': 'серця два two hearts love',
  '💞': 'серця обертаються revolving hearts', '💓': 'серце б\'ється beating heart',
  '💗': 'серце росте growing heart', '💖': 'серце зірки sparkling heart',
  '💝': 'серце стрічка gift heart ribbon', '💘': 'серце стріла cupid arrow heart',
  '💟': 'серце декор heart decoration', '♥️': 'серце heart suit',
  '🫀': 'серце анатомія anatomical heart organ', '💋': 'поцілунок губи kiss lips mark',
  '😻': 'кіт серця cat heart eyes love', '💑': 'пара couple heart',
  '💏': 'поцілунок пара couple kiss',
  // === Тварини ===
  '🐶': 'собака пес dog puppy', '🐱': 'кіт котик cat kitty',
  '🐭': 'миша мишка mouse', '🐹': 'хом\'як hamster',
  '🐰': 'кролик заєць rabbit bunny', '🦊': 'лисиця fox',
  '🐻': 'ведмідь bear', '🐼': 'панда panda',
  '🐻‍❄️': 'полярний ведмідь polar bear arctic', '🐨': 'коала koala',
  '🐯': 'тигр tiger', '🦁': 'лев lion king',
  '🐮': 'корова cow', '🐷': 'свиня порося pig',
  '🐸': 'жаба frog', '🐵': 'мавпа monkey',
  '🙈': 'не бачу see no evil monkey', '🙉': 'не чую hear no evil monkey',
  '🙊': 'не кажу speak no evil monkey', '🐔': 'курка chicken',
  '🐧': 'пінгвін penguin', '🐦': 'птах bird',
  '🦅': 'орел eagle', '🦆': 'качка duck',
  '🦉': 'сова owl', '🐺': 'вовк wolf',
  '🐗': 'кабан boar', '🐴': 'кінь horse',
  '🦄': 'єдиноріг unicorn magic', '🐝': 'бджола bee honey',
  '🐛': 'гусінь bug caterpillar', '🦋': 'метелик butterfly',
  '🐌': 'равлик snail slow', '🐞': 'сонечко ladybug',
  '🐜': 'мураха ant', '🦟': 'комар mosquito',
  '🐍': 'змія snake', '🐢': 'черепаха turtle slow',
  '🦎': 'ящірка lizard', '🐙': 'восьминіг octopus',
  // === Їжа ===
  '🍎': 'яблуко apple red', '🍐': 'груша pear',
  '🍊': 'мандарин апельсин orange tangerine', '🍋': 'лимон lemon yellow sour',
  '🍌': 'банан banana', '🍉': 'кавун watermelon',
  '🍇': 'виноград grapes', '🍓': 'полуниця strawberry',
  '🫐': 'чорниця blueberry', '🍈': 'диня melon',
  '🍒': 'вишня cherry', '🍑': 'персик peach',
  '🥭': 'манго mango', '🍍': 'ананас pineapple',
  '🥥': 'кокос coconut', '🥝': 'ківі kiwi',
  '🍅': 'помідор tomato', '🥑': 'авокадо їжа avocado food',
  '🥦': 'броколі broccoli', '🌽': 'кукурудза corn',
  '🌶️': 'перець chili pepper hot spicy', '🫑': 'перець bell pepper',
  '🥒': 'огірок cucumber', '🧄': 'часник garlic',
  '🧅': 'цибуля onion', '🍄': 'гриб mushroom',
  '🥜': 'арахіс peanut nut', '🫘': 'квасоля beans',
  '🍞': 'хліб bread', '🥐': 'круасан croissant',
  '🥖': 'багет baguette french bread', '🧀': 'сир cheese',
  '🍕': 'піца їжа pizza food', '🌭': 'хот-дог hotdog',
  '🍔': 'бургер гамбургер burger hamburger', '🍟': 'картопля фрі fries chips',
  '🌮': 'тако taco mexican', '🍣': 'суші sushi japanese',
  '🍰': 'торт десерт свято cake dessert birthday', '☕': 'кава чашка перерва coffee cup break',
  // === Активності ===
  '⚽': 'футбол soccer football', '🏀': 'баскетбол basketball',
  '🏈': 'американський футбол football american', '⚾': 'бейсбол baseball',
  '🥎': 'софтбол softball', '🎾': 'теніс tennis',
  '🏐': 'волейбол volleyball', '🏉': 'регбі rugby',
  '🥏': 'фрізбі frisbee disc', '🎱': 'більярд pool billiards',
  '🏓': 'пінг-понг ping pong table tennis', '🏸': 'бадмінтон badminton',
  '🏒': 'хокей hockey', '🥊': 'бокс boxing',
  '🎿': 'лижі skiing', '⛷️': 'лижник skier skiing',
  '🏂': 'сноуборд snowboard', '🪂': 'парашут parachute skydive',
  '🏋️': 'тяжка атлетика weightlifting gym', '🤸': 'гімнастика gymnastics cartwheel',
  '🎮': 'геймпад гра gamepad game controller', '🕹️': 'джойстик joystick arcade',
  '🎲': 'кості кубик dice game chance', '🧩': 'пазл puzzle piece',
  '♟️': 'шахи chess pawn', '🎯': 'ціль влучив точно target bullseye dart',
  '🎳': 'боулінг bowling', '🎪': 'цирк circus tent',
  '🎨': 'палітра малюю дизайн art paint design palette', '🎭': 'театр маски theater drama masks',
  '🎤': 'мікрофон караоке microphone karaoke sing', '🎧': 'навушники музика headphones music listen',
  '🎵': 'нота музика note music', '🎶': 'ноти музика notes music melody',
  '🎹': 'піаніно клавіші piano keys keyboard', '🥁': 'барабан drum',
  '🎸': 'гітара guitar rock', '🎺': 'труба trumpet',
  '🎻': 'скрипка violin', '🪗': 'акордеон accordion',
  // === Подорожі ===
  '🚗': 'машина авто car automobile', '🚕': 'таксі taxi cab',
  '🚌': 'автобус bus', '🏎️': 'перегони гонка race car formula',
  '🚑': 'швидка ambulance emergency', '🚒': 'пожежна fire truck engine',
  '🚐': 'мінівен minibus van', '🛻': 'пікап pickup truck',
  '🚚': 'вантажівка truck delivery', '🚂': 'поїзд локомотив train locomotive steam',
  '🚆': 'поїзд train', '🚇': 'метро subway metro underground',
  '🚊': 'трамвай tram streetcar', '✈️': 'літак airplane flight travel',
  '🛫': 'зліт takeoff departure', '🛬': 'посадка landing arrival',
  '🚀': 'ракета запуск швидко rocket launch ship space', '🛸': 'нло ufo alien spaceship',
  '🚁': 'гелікоптер helicopter chopper', '⛵': 'парусник sailboat',
  '🚢': 'корабель ship cruise', '⛽': 'заправка gas fuel petrol',
  '🚦': 'світлофор traffic light', '🏗️': 'будівництво construction crane',
  '🏠': 'дім будинок house home', '🏢': 'офіс будівля office building',
  '🏰': 'замок castle', '🗼': 'вежа tower',
  '🗽': 'свобода статуя liberty statue', '⛪': 'церква church',
  '🕌': 'мечеть mosque', '🛕': 'храм temple hindu',
  '⛩️': 'торії torii shrine japan', '🏖️': 'пляж beach umbrella',
  '🏔️': 'гора mountain snow peak', '🌋': 'вулкан volcano eruption',
  '🗻': 'фудзі fuji mountain japan', '🏕️': 'кемпінг camping tent',
  '🌅': 'схід сонця ранок sunrise morning', '🌄': 'захід sunset mountain',
  // === Символи ===
  '💡': 'ідея лампочка думка idea bulb light', '🔥': 'вогонь пожар полум\'я fire hot flame lit',
  '✨': 'блиск іскри магія sparkles shine magic stars', '⭐': 'зірка star',
  '🌟': 'сяйво glowing star bright', '💫': 'запаморочення зірка dizzy star',
  '⚡': 'блискавка швидко струм bolt fast power lightning', '🌈': 'веселка rainbow',
  '☀️': 'сонце sun sunny', '🌙': 'місяць moon crescent night',
  '⛅': 'хмара cloud partly', '❄️': 'сніжинка snow flake winter cold',
  '💧': 'крапля water drop', '🌊': 'хвиля wave ocean sea',
  '🎉': 'свято вечірка вітаю party tada congrats celebration', '🎊': 'конфеті confetti ball party',
  '🎁': 'подарунок gift present box', '🏆': 'кубок перемога приз trophy win champion',
  '🥇': 'золото перше місце gold medal first', '🎖️': 'медаль medal military honor',
  '📌': 'кнопка прикріпити закріпити pin pushpin', '📈': 'графік зростання вгору chart growth up trending',
  '📉': 'графік падіння вниз chart down trending decline', '💻': 'ноутбук код робота laptop code dev computer',
  '🛠️': 'інструменти ремонт tools fix build wrench', '⏳': 'чекаю пісок час wait hourglass time',
  '💎': 'алмаз діамант цінно gem diamond jewel', '📑': 'документи аркуші папка docs papers',
  '✅': 'готово зроблено так done check ok approved',
  '❌': 'ні відмова скасувати no cross cancel reject', '⚠️': 'увага попередження warning caution alert',
  '🔔': 'дзвоник сповіщення bell notification ring', '💬': 'повідомлення чат speech bubble comment',
  '💭': 'думка thought bubble cloud', '🗯️': 'гнів anger bubble speech',
  '♻️': 'переробка recycle green eco', '🔑': 'ключ key lock access',
  '🪄': 'палочка магія wand magic spell',
};

export const ReactionPickerModal: React.FC<ReactionPickerModalProps> = ({
  isOpen,
  onClose,
  onSelectEmoji,
}) => {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [activeCategory, setActiveCategory] = useState('nightpost');
  const [recentEmojis, setRecentEmojis] = useState<string[]>(loadRecent);
  const gridRef = useRef<HTMLDivElement>(null);
  const tabsRef = useRef<HTMLDivElement>(null);


  // Reset state on open
  useEffect(() => {
    if (isOpen) {
      setSearch('');
      setRecentEmojis(loadRecent());
      const recent = loadRecent();
      setActiveCategory(recent.length > 0 ? 'recent' : 'nightpost');
    }
  }, [isOpen]);

  const categoriesWithRecent = useMemo(() => {
    const cats = [...emojiCategories];
    cats[0] = { ...cats[0], emojis: recentEmojis };
    return cats;
  }, [recentEmojis]);

  const triggerConfetti = useCallback(() => {
    // canvas-confetti розуміє лише hex, тож `var(--ph-*)` йому не передаси:
    // барви токенів читаються з кореня в мить пострілу — і йдуть за темою.
    const root = getComputedStyle(document.documentElement);
    const colors = CONFETTI_TOKENS.map((name) => root.getPropertyValue(name).trim()).filter(Boolean);
    void import('canvas-confetti')
      .then(({ default: confetti }) => {
        confetti({
          particleCount: 35,
          spread: 60,
          origin: { y: 0.7 },
          ...(colors.length > 0 ? { colors } : {}),
        });
      })
      .catch(() => {});
  }, []);

  const handlePick = useCallback((emoji: string) => {
    soundFx.playSend();
    if (['❤️', '🔥', '🎉', '🚀', '✨'].includes(emoji)) {
      triggerConfetti();
    }
    // Update recent emojis
    const updated = [emoji, ...recentEmojis.filter((e) => e !== emoji)].slice(0, MAX_RECENT);
    setRecentEmojis(updated);
    saveRecent(updated);
    onSelectEmoji(emoji);
    onClose();
  }, [recentEmojis, onSelectEmoji, onClose, triggerConfetti]);

  if (!isOpen) return null;

  const allEmojis = categoriesWithRecent.flatMap((c) => c.emojis);
  const query = search.trim().toLowerCase();
  const filteredEmojis = query
    ? [...new Set(allEmojis)].filter((e) => e.includes(search.trim()) || (EMOJI_WORDS[e] || '').includes(query))
    : null;

  const activeCat = categoriesWithRecent.find((c) => c.id === activeCategory);
  const showEmojis = filteredEmojis ?? activeCat?.emojis ?? [];

  return (
    <FormSheet open onClose={onClose} title={t('reactionPickerModal.search')} hideTitle testId="reaction-picker">
      <div className="h-[60dvh] md:h-[420px] flex flex-col text-ink">
        {/* Search */}
        <div className="flex items-center gap-2 p-3 pb-0">
          <div className="flex items-center gap-2 flex-1 px-3 py-2 bg-surface border border-line-strong focus-within:border-accent-line rounded-control min-h-[44px] transition-colors">
            <Search className="w-4 h-4 text-ink-2 shrink-0" strokeWidth={1.75} />
            <input
              type="text"
              autoFocus
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('reactionPickerModal.search')}
              className="w-full text-footnote text-ink placeholder:text-ink-3 focus:outline-none bg-transparent"
            />
            {search && (
              <button
                onClick={() => setSearch('')}
                aria-label={t('reactionPickerModal.clear')}
                /* Зона дотику 44, у розкладці — ті самі 20: -m-3 забирає надлишок. */
                className="w-11 h-11 -m-3 shrink-0 flex items-center justify-center text-ink-2"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            )}
          </div>
        </div>

        {/* Category Tabs — scrollable horizontal strip */}
        {!search && (
          <div
            ref={tabsRef}
            className="flex items-center gap-0.5 overflow-x-auto no-scrollbar px-3 py-2 border-b border-white/8"
          >
            {categoriesWithRecent.map((cat) => {
              if (cat.id === 'recent' && cat.emojis.length === 0) return null;
              const Icon = cat.icon;
              const isActive = activeCategory === cat.id;
              return (
                <button
                  key={cat.id}
                  onClick={() => {
                    setActiveCategory(cat.id);
                    gridRef.current?.scrollTo({ top: 0 });
                  }}
                  className={`p-2 min-w-[40px] min-h-[40px] rounded-control flex items-center justify-center transition-all shrink-0 ${
                    isActive
                      ? 'bg-accent/15 text-accent-text'
                      : 'text-ink-2 hover:text-ink hover:bg-raised'
                  }`}
                  title={t(cat.name)}
                >
                  <Icon className="w-[18px] h-[18px]" strokeWidth={1.75} />
                </button>
              );
            })}
          </div>
        )}

        {/* Category title */}
        {!search && activeCat && (
          <div className="px-3 pt-2 pb-1">
            <span className="text-caption font-semibold text-ink-2 ">{t(activeCat.name)}</span>
          </div>
        )}

        {/* Emoji Grid */}
        <div ref={gridRef} className="flex-1 overflow-y-auto overscroll-contain px-2 pb-3">
          {showEmojis.length === 0 ? (
            <p className="py-8 text-center text-caption text-ink-2">
              {search ? t('reactionPickerModal.noMatch', { trim: search.trim() }) : t('reactionPickerModal.empty')}
            </p>
          ) : (
            <div className="grid grid-cols-8 sm:grid-cols-8 gap-0.5">
              {showEmojis.map((emoji, idx) => (
                <button
                  key={`${emoji}-${idx}`}
                  onClick={() => handlePick(emoji)}
                  className="w-full aspect-square min-h-[44px] flex items-center justify-center text-[24px] sm:text-[22px] rounded-control hover:bg-raised active:scale-90 transition-all duration-100"
                >
                  {!search && activeCat?.id === 'nightpost' ? <ReactionGlyph value={emoji} size={28} /> : emoji}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </FormSheet>
  );
};

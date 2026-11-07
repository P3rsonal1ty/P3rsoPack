# P3rsoPack

Плагин для **Paper / Folia 26.3**: кастомные предметы, интерфейсы (GUI), блоки и мебель для обычного
ванильного клиента без модов. Ресурспак собирается автоматически из конфигов и папки `pack/`,
раздаётся встроенным HTTP-сервером и отправляется игроку ещё до входа в мир.

- **Предметы**: компонент `item_model` (без устаревшего CustomModelData), имя/лор в MiniMessage, атрибуты,
  зачарования, прочность, броня через `equippable` с автогенерацией equipment-ассета.
- **GUI**: окна из YAML, фон окна — картинка через глиф шрифта и отрицательные сдвиги в заголовке,
  действия на клики (команды, переходы между окнами, выдача предметов, звуки, сообщения).
- **Глифы и HUD**: `<glyph:id>` и `<shift:N>` в любом тексте, постоянный HUD в action bar.
- **Блоки**: до 674 блоков на состояниях нотного блока; свои дропы, опыт, звуки и твёрдость
  (скорость копания честно считается сервером и клиентом через атрибут `block_break_speed`).
- **Мебель**: `item_display` + хитбокс `interaction` или barrier-блоки, повороты, сидения.
- **Ресурспак**: правильный `pack_format` 26.3 (97), детерминированный zip — **одинаковый SHA-1 на всех
  серверах сети** при одинаковых конфигах; режим внешнего URL для сети за Velocity.
- **Понятные ошибки**: `items/ruby.yml:12: неизвестный материал 'DIAMOND_SWORDD' (может быть, 'DIAMOND_SWORD'?)`.
- **Folia**: `folia-supported: true`, только региональные/entity/async-планировщики.

## Установка

1. Нужны Java 25 и сервер **Paper или Folia 26.3**.
2. Положите `P3rsoPack-<версия>.jar` в `plugins/` и запустите сервер — в `plugins/P3rsoPack/` появятся
   примеры (рубин, рубиновый меч и броня, руда, стул, стол, магазин).
3. Для кастомных блоков включите в `config/paper-global.yml` и перезапустите сервер:
   ```yaml
   block-updates:
     disable-noteblock-updates: true
   ```
4. Откройте порт пака (по умолчанию **8164/TCP**) или настройте `pack.hosting` (см. ниже).
5. Зайдите на сервер: `/menu` — готовое главное меню; `/p3rsopack give @s ruby`, `/p3rsopack gui shop`.

Сборка из исходников:

```bash
./gradlew build
```

Готовый jar — `build/libs/P3rsoPack-<версия>.jar`.

## Структура папки плагина

```
plugins/P3rsoPack/
├── config.yml          — namespace, хостинг и отправка пака, HUD
├── items/*.yml         — предметы
├── guis/*.yml          — окна
├── blocks/*.yml        — блоки (у каждого автоматически есть предмет с тем же id)
├── furniture/*.yml     — мебель (у каждой автоматически есть предмет с тем же id)
├── glyphs/*.yml        — картинки для текста: фоны окон, HUD, иконки
├── pack/               — ваши ресурсы в структуре ресурспака:
│   ├── pack.png
│   └── assets/p3rso/
│       ├── textures/item/…   textures/block/…   textures/gui/…
│       ├── textures/entity/equipment/humanoid/…  (броня на игроке)
│       └── models/…           (свои 3D-модели, например из Blockbench)
├── data/block-variations.yml — какие состояния нотного блока заняты какими блоками (не удалять!)
└── output/pack.zip     — собранный пак (для внешнего хостинга)
```

Файлов в каждой папке может быть сколько угодно, подпапки тоже читаются. Один файл может содержать
несколько определений: каждый раздел верхнего уровня — это id. Id без префикса получает `namespace`
из `config.yml` (`ruby` → `p3rso:ruby`). Допустимы только строчные латинские буквы, цифры и `_ . - /`.

После любых изменений: **`/p3rsopack reload`** — конфиги перечитаются, пак пересоберётся и (если он
изменился) будет разослан игрокам онлайн. Ошибки выводятся в консоль и в чат с указанием файла и строки;
повторно посмотреть их — `/p3rsopack errors`.

## Пример: добавить предмет

1. Нарисуйте текстуру 16×16 и положите её в `pack/assets/p3rso/textures/item/sapphire.png`.
2. Создайте `items/gems.yml`:
   ```yaml
   sapphire:
     material: PAPER
     name: "<blue>Сапфир"
     lore:
       - "Синий камень"
     texture: item/sapphire
   ```
3. `/p3rsopack reload`, затем `/p3rsopack give @s sapphire`.

### Все поля предмета

| Поле | Описание |
|---|---|
| `material` | базовый ванильный предмет (по умолчанию `PAPER`) — определяет поведение |
| `name`, `lore` | MiniMessage; поддерживаются `<glyph:id>`, `<shift:N>` |
| `texture` | текстура (или список текстур-слоёв) → модель сгенерируется с `item/generated` |
| `handheld: true` | держать как инструмент (`item/handheld`) |
| `textures` + `parent` | произвольные слоты текстур и родитель модели |
| `model` | своя модель из `pack/assets/<ns>/models/…` (например `furniture/chair`) |
| `item-model` | готовое определение предмета, напр. `minecraft:sunflower` (ничего не генерируется) |
| `max-stack-size`, `max-damage`, `unbreakable`, `glint`, `hide-tooltip` | компоненты предмета |
| `attributes` | список `{attribute, amount, operation, slot}`; заменяют стандартные атрибуты материала |
| `enchantments` | `{sharpness: 2}` |
| `equippable` | `{slot, asset, equip-sound, camera-overlay, swappable, damage-on-hurt}` |

Свой файл `pack/assets/<ns>/items/<id>.json` (например с `select`/`condition`) имеет приоритет над
сгенерированным. Текстуры не из `textures/item/` и `textures/block/` автоматически добавляются в атлас.

### Броня

```yaml
sapphire_helmet:
  material: LEATHER_HELMET
  texture: item/sapphire_helmet
  equippable:
    slot: head
    asset: sapphire
  attributes:
    - {attribute: armor, amount: 3, slot: head}
```

Текстура брони на игроке: `pack/assets/p3rso/textures/entity/equipment/humanoid/sapphire.png`
(для поножей — `humanoid_leggings/sapphire.png`). Файл `equipment/sapphire.json` создаётся автоматически.

## Пример: окно (GUI)

1. Фон: картинка 176×222 (как окно сундука на 6 рядов) в `pack/assets/p3rso/textures/gui/bank.png`.
2. `glyphs/bank.yml`:
   ```yaml
   bank_bg:
     texture: gui/bank
     height: 222
     ascent: 13   # совмещает верх картинки с верхом окна
   ```
3. `guis/bank.yml`:
   ```yaml
   bank:
     title: "<white>Банк"
     rows: 6
     background: bank_bg
     items:
       deposit:
         slot: 20
         item: sapphire
         name: "<green>Положить"
         left-actions:
           - "command: bank deposit"
           - "sound: entity.experience_orb.pickup"
         right-actions:
           - "open: bank_history"
       close:
         slots: [49]
         material: BARRIER
         name: "<red>Закрыть"
         actions:
           - close
   ```
4. `/p3rsopack reload`, `/p3rsopack gui bank`.

Слоты: число, диапазон `"10-16"` или список. Действия:

| Действие | Что делает |
|---|---|
| `command: <cmd>` | команда от имени игрока |
| `console: <cmd>` | команда от консоли |
| `open: <окно>` / `close` / `refresh` | открыть другое окно / закрыть / перерисовать |
| `message: <текст>` / `actionbar: <текст>` | сообщение в чат / над хотбаром |
| `sound: <звук> [громкость] [тон]` | звук игроку |
| `give: <предмет> [кол-во]` | выдать кастомный предмет |

`%player%` заменяется на ник. Клики: `actions` (любой), `left-actions`, `right-actions`,
`shift-left-actions`, `shift-right-actions`, `middle-actions`, `drop-actions`. `open-actions` —
при открытии окна. `background-offset` / `title-offset` двигают фон и текст заголовка, если ваша картинка
другого размера.

### Готовое главное меню

Сразу после установки у любого игрока работает **`/menu`**: тёмная панель с золотой рамкой, три карточки
(коллекция предметов, рубиновая лавка, ночное зрение) и панель кнопок (профиль, помощь, закрыть, GitHub,
подсказка про ресурспак). Конфиг — `guis/main_menu.yml`, фон — `pack/assets/p3rso/textures/gui/main_menu.png`.

<p align="center"><img src="docs/main_menu.png" width="352" alt="Главное меню"> <img src="docs/collection.png" width="352" alt="Коллекция"></p>

Карточка кликается целиком: вокруг иконки лежат невидимые предметы `blank` с теми же действиями
(через якоря YAML `&имя` / `*имя`). Лавка и коллекция выдают предметы бесплатно, поэтому закрыты правом
`p3rsopack.admin` — обычный игрок увидит «Нет доступа».

Поля окна для команд и прав:

| Поле | Описание |
|---|---|
| `command: menu` | своя команда, открывающая окно (`/menu`). Новые команды появляются **после перезапуска** сервера |
| `permission: some.perm` | право на открытие окна (командой, через `open:` или API); без поля — доступно всем |

## Блоки

```yaml
sapphire_ore:
  name: "<blue>Сапфировая руда"
  texture: block/sapphire_ore           # или textures: {top, bottom, side} / {end, side} / model: …
  hardness: 3.0
  tool: pickaxe                         # pickaxe, axe, shovel, hoe, sword, shears, none
  requires-tool: true
  silk-touch: true
  drops:
    - {item: sapphire, amount: 1-3}
    - {material: COBBLESTONE, chance: 0.5}
  exp: 2-5
  sounds: {place: block.stone.place, break: block.stone.break, hit: block.stone.hit}
```

Блок ставится ПКМ его предметом. Состояния нотного блока, назначенные блокам, сохраняются в
`data/block-variations.yml`: добавление новых блоков не меняет вид уже поставленных. Можно задать
вручную: `variation: 1..674`. Ванильные нотные блоки становятся декоративными (не меняют ноту),
см. `blocks.reset-vanilla-note-blocks`.

## Мебель

```yaml
armchair:
  name: "Кресло"
  model: furniture/armchair             # pack/assets/p3rso/models/furniture/armchair.json
  rotation-step: 45
  hitbox: {type: interaction, width: 1.0, height: 1.0}
  # hitbox: {type: barrier, blocks: [[0,0,0], [1,0,0]]}   — твёрдые блоки, смещения поворачиваются вместе с мебелью
  seat: {height: 0.55}                  # или seats: [[x, y, z], ...]
  display: {transform: none, scale: 1.0, translation: [0, 0, 0], brightness: 15}
  drop: true
```

ПКМ предметом — поставить (мебель повернётся к игроку), ЛКМ — сломать (проверяется защита регионов
через `BlockBreakEvent`/`BlockPlaceEvent`), ПКМ по мебели — сесть.

## Глифы и HUD

```yaml
coin:
  texture: hud/coin
  height: 9
  ascent: 8
```

Используйте `<glyph:coin>` в именах, лоре, заголовках окон, сообщениях. `<shift:-20>` сдвигает текст.
Постоянный HUD — `config.yml → hud` (`text: "<shift:-91><glyph:hud_bar>"`).

## Ресурспак и сеть серверов (Velocity)

- **`mode: self`** (по умолчанию) — пак раздаёт сам плагин на `bind-address:port`. URL для игрока
  строится из `public-address`, а если он пуст — из адреса, по которому игрок подключился.
- **`mode: external`** — загрузите `output/pack.zip` на свой хостинг/CDN и укажите `external-url`.
  `external-sha1` можно оставить пустым — возьмётся SHA-1 локально собранного пака.
- **`mode: none`** — пак не раздаётся (например, его отправляет прокси).

Отправка (`pack.send.phase`): `configuration` — пак загружается до входа в мир (рекомендуется),
`join` — после входа, `none` — не отправлять.

**Один пак на всю сеть.** Zip собирается детерминированно: одинаковые конфиги и файлы `pack/` дают побайтно
одинаковый пак и одинаковый SHA-1 на всех серверах, а UUID пака зависит только от `namespace`. Поэтому при
переходе между серверами клиент не перекачивает пак. Удобная схема: держите `items/ guis/ glyphs/ blocks/
furniture/ pack/ data/` в одном git-репозитории/общей папке, на одном сервере (или CDN) раздавайте пак
(`mode: self` или `external`), на остальных — `mode: external` с тем же URL. `data/block-variations.yml`
должен быть одинаковым на всех серверах.

## Команды и права

Все команды требуют `p3rsopack.admin` (по умолчанию — операторы). Алиас: `/pp`.

| Команда | |
|---|---|
| `/p3rsopack reload` | перечитать конфиги, пересобрать и разослать пак |
| `/p3rsopack give <игроки> <предмет> [кол-во]` | выдать предмет (с автодополнением) |
| `/p3rsopack gui <окно> [игроки]` | открыть окно |
| `/p3rsopack list [items\|blocks\|furniture\|guis\|glyphs]` | список id |
| `/p3rsopack errors` | ошибки и предупреждения последней загрузки |
| `/p3rsopack pack [info\|send [игроки]]` | URL/SHA-1 пака, отправить пак |

## API для других плагинов

```java
import ru.p3rso.pack.api.P3rsoPack;

ItemStack ruby = P3rsoPack.item("ruby").orElseThrow();          // новый ItemStack
Optional<String> id = P3rsoPack.idOf(player.getInventory().getItemInMainHand());
boolean isRuby = P3rsoPack.is(stack, "ruby");
P3rsoPack.openGui(player, "shop");                               // с любого потока
Component icon = P3rsoPack.glyph("coin");
Optional<String> block = P3rsoPack.blockAt(block);               // на потоке региона
P3rsoPack.placeFurniture(location, "chair", 90f);
```

Подпишитесь на `ru.p3rso.pack.api.P3rsoPackReloadEvent`, чтобы обновить свои кэши после `/p3rsopack reload`.
В `plugin.yml` добавьте `depend: [P3rsoPack]` или `softdepend: [P3rsoPack]`.

## Ограничения

- Надпись «Инвентарь» над инвентарём игрока рисуется поверх фона окна — учитывайте это в дизайне.
- Нотные блоки целиком отданы под кастомные блоки: ванильные нотные блоки не меняют ноту.
- Хитбокс `interaction` не поворачивается (он всегда квадратный); для сложных форм используйте `barrier`.
- Клиенты других версий (через ViaVersion) могут не поддерживать формат пака 26.3.

## Разработка

```bash
./gradlew test    # юнит-тесты загрузчика конфигов и генератора пака
./gradlew build   # jar в build/libs/
```

Код разделён на независимое от Bukkit ядро (`config`, `model`, `pack` — покрыто тестами) и слой плагина
(`item`, `gui`, `block`, `furniture`, `host`, `command`).

## Лицензия

MIT — см. [LICENSE](LICENSE).

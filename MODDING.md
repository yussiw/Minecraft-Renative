# Criando mods para o Blocos

Mods podem ser escritos em **Lua** (linguagem principal) e, com suporte menor, **JavaScript**.
A API é **idêntica** nas duas linguagens.

## Formatos de mod

| Formato | Como |
|---|---|
| **.zip** com `mod.json` | pacote completo: scripts, texturas, ícone |
| **.lua / .js solto** | mod de um arquivo só; metadados em comentários (`--@name`, `--@description`, `--@version`, `--@author`) |
| **.jar / .zip de mod Java** | só assets (blocos de cubo, itens, receitas) e JSON de comportamento; o código Java **não** roda |

Para importar: **Menu → Mods → Adicionar mod...**

### Estrutura do .zip (mod.json na raiz)
```
meu_mod.zip
├─ mod.json        (obrigatório para ter nome/ícone/descrição)
├─ icon.png        (ícone mostrado no menu)
├─ main.lua        (e/ou main.js)
├─ util.lua        (opcional, carregado com require("util"))
└─ textures/
   ├─ ruby.png     (16x16 ou maior, quadrada)
   └─ ruby_ore.png
```

### mod.json
```json
{
  "id": "meumod",
  "name": "Meu Mod",
  "version": "1.0.0",
  "author": "Você",
  "description": "O que o mod faz.",
  "icon": "icon.png",
  "scripts": ["main.lua", "extra.js"]
}
```
Só `id` e `name` importam de fato. `scripts` é opcional (padrão: `main.lua` e depois `main.js`).
O `id` vira o prefixo dos seus blocos/itens: `ruby` dentro do mod `meumod` é `meumod:ruby`.

## Ciclo de vida

1. **Carregamento**: o *nível principal* do script roda uma vez. **Só aqui** dá pra usar
   `blocks.register`, `items.register` e `recipes.*`. `world.*` e `player.*` ainda não existem.
2. **ready**: o mundo existe. Todas as funções liberadas.
3. **Jogo**: seus eventos e timers rodam.

Ativar, desativar ou excluir um mod vale ao **reiniciar** (o botão vira *Aplicar e reiniciar*).

## Identificadores

`"ruby"` = do seu mod · `"outro:ruby"` = de outro mod · `"minecraft:dirt"` ou `"dirt"` = jogo base.
Base disponível: `grass_block dirt stone sand oak_log oak_leaves oak_planks cobblestone snow_block gravel glass water bricks short_grass dandelion oxeye_daisy apple` (+ `air` em `world.set_block`).

## Registro (só no carregamento)

```lua
blocks.register("ruby_ore", {
  name = "Minério de Rubi",
  texture = "ruby_ore",                      -- ou textures = {top=..., side=..., bottom=...}
  hardness = 3, light = 0, damage = 0, tool = "pickaxe",
  drop = "ruby", drop_count = 1,             -- drop = "none" não solta nada
  worldgen = { min_y = 4, max_y = 40, tries = 6, vein = 4 },
  on_use   = function(e) end,                -- clique direito no bloco
  on_break = function(e) end,                -- retornar false cancela
  on_place = function(e) end,                -- retornar false cancela
})

items.register("ruby", {
  name = "Rubi", texture = "ruby",
  food = { hunger = 4, saturation = 2 }, heal = 0,
  tool = { type = "pickaxe", speed = 6, attack = 5 },
  on_use = function(e) end,                  -- retornar false cancela a ação padrão
})

recipes.shaped({"RRR"," P "," P "}, {R="ruby", P="minecraft:oak_planks"}, "ruby_pickaxe", 1)
recipes.shapeless({"ruby_block"}, "ruby", 9)
recipes.add({ type="crafting_shaped", ... })   -- formato de receita do Minecraft
blocks.exists(id)  items.exists(id)
```
`on_use` de bloco também aceita uma tabela declarativa: `{ give="ruby", count=1, heal=2, feed=2, message="Oi", consume=false, replace="air", cooldown=0.5 }`.

Texturas são procuradas em `textures/<nome>.png`, `textures/block(s)|item(s)/<nome>.png` e `assets/<id>/textures/...`.
**Limites:** 83 blocos e 44 texturas de mods no total (todos os mods juntos). Item usa 1 textura; bloco com `top/side/bottom` diferentes usa até 3.

## Mods só com JSON (sem Lua/JS)

Dá para criar blocos, itens e receitas apenas com arquivos JSON dentro do `.zip`. Não precisa de `main.lua`/`main.js`.
`<ns>` é o `id` do `mod.json`.

```
meumod.zip
├─ mod.json
├─ textures/ruby.png, ruby_ore.png
└─ data/<ns>/
   ├─ blocks/ruby_ore.json
   ├─ items/ruby.json
   └─ recipes/ruby_pickaxe.json
```

O nome do arquivo vira o id: `ruby_ore.json` → `meumod:ruby_ore`.

### Bloco — `data/<ns>/blocks/<nome>.json`
```json
{
  "name": "Minério de Rubi",
  "texture": "ruby_ore",
  "hardness": 3, "light": 0, "damage": 0, "tool": "pickaxe",
  "drop": "ruby", "drop_count": 1,
  "worldgen": { "min_y": 4, "max_y": 40, "tries": 6, "vein": 4 },
  "on_use": { "message": "Oi", "heal": 2 }
}
```
- Texturas: `"texture"` (todas as faces) ou `"textures": {"top": "..", "side": "..", "bottom": ".."}`.
- `drop = "none"` não solta nada.
- `on_use` aceita `give`, `count`, `heal`, `feed`, `message`, `consume`, `replace`, `cooldown`.

### Item — `data/<ns>/items/<nome>.json`
```json
{
  "name": "Rubi",
  "texture": "ruby",
  "food": { "hunger": 4, "saturation": 2 },
  "heal": 0,
  "tool": { "type": "pickaxe", "speed": 6, "attack": 5 }
}
```
Sem `texture`, procura `item/<nome>.png`.

### Receita — `data/<ns>/recipes/<nome>.json` (ou `recipe/`)
Formato de receita do Minecraft. Tipos suportados: `crafting_shaped` e `crafting_shapeless` (os outros são ignorados).
```json
{
  "type": "minecraft:crafting_shaped",
  "pattern": ["RRR", " P ", " P "],
  "key": { "R": "ruby", "P": "minecraft:oak_planks" },
  "result": { "item": "ruby_pickaxe", "count": 1 }
}
```
```json
{
  "type": "minecraft:crafting_shapeless",
  "ingredients": ["ruby", "ruby"],
  "result": { "item": "ruby_block", "count": 1 }
}
```
Ingredientes podem ser texto (`"ruby"`), objeto (`{"item": ".."}` / `{"tag": ".."}`) ou lista de alternativas.

### Limites e diferenças
- Mesmos limites dos scripts: 83 blocos e 44 texturas de mods no total.
- Sem callbacks (`on_break`, `on_place`, eventos, timers): só o `on_use` declarativo. Para isso, use Lua/JS.
- JSON e script podem ser combinados no mesmo mod.

## Eventos — `events.on(nome, função)`

| Evento | Argumento | Retorno |
|---|---|---|
| `ready` | — | — |
| `tick` | `dt` (segundos, todo quadro, jogo não pausado) | — |
| `block_break` | `{x,y,z,block}` | `false` cancela |
| `block_place` | `{x,y,z,block}` | `false` cancela |
| `block_use` | `{x,y,z,block}` (clique direito, sem agachar) | `false` consome o clique sem fazer nada |
| `item_use` | `{item,slot,count}` (itens de mod e maçã) | `false` cancela |
| `hurt` | `{amount,health}` | `false` cancela; um **número** substitui o dano |
| `death`, `respawn` | — | — |

Ações feitas pela API (ex.: `player.damage`) **não** disparam eventos, para evitar recursão.

## Timers
```lua
local id = timer.after(5, function() ... end)   -- uma vez
local id = timer.every(60, function() ... end)  -- repetido
timer.cancel(id)
```

## Mundo (após `ready`)
| Função | |
|---|---|
| `world.get_block(x,y,z)` | `"minecraft:dirt"`… ou nil se o chunk não está carregado |
| `world.set_block(x,y,z,id)` | true/false |
| `world.fill(x1,y1,z1,x2,y2,z2,id)` | blocos colocados (máx. 32768) |
| `world.explode(x,y,z,raio,soltar)` | blocos removidos (raio máx. 12) |
| `world.top_y(x,z)` | y do bloco mais alto, ou nil |
| `world.is_loaded(x,z)` | bool |
| `world.get_time()` / `world.set_time(h)` | hora 0–24 (6 = amanhecer) |
| `world.drop(item,n,x,y,z)` | solta item no chão |

## Jogador (após `ready`)
| Função | |
|---|---|
| `player.pos()` / `player.set_pos(x,y,z)` | `{x,y,z}` |
| `player.velocity()` / `player.set_velocity(x,y,z)` | blocos/s (pulo ≈ 8.4) |
| `player.look()` | `{yaw,pitch,dx,dy,dz}` |
| `player.target()` | bloco mirado `{x,y,z,block,nx,ny,nz}` ou nil |
| `player.health()` `set_health(v)` `damage(v)` `heal(v)` | 0–20 |
| `player.food()` `set_food(n)` | 0–20 |
| `player.gamemode()` `set_gamemode("creative"/"survival")` `set_flying(b)` | |
| `player.give(item,n)` | devolve o que **não coube** |
| `player.take(item,n)` | true se tinha o bastante |
| `player.count(item)` | quantidade total |
| `player.held()` | `{item,count,slot}` ou nil |
| `player.slot(i)` `set_slot(i,item,n)` `selected()` | slots 0–35 (0–8 = hotbar) |
| `player.message(texto, segundos)` | mensagem na tela |

## Outros
```lua
log("texto", 123)                    -- vai para o log do mod (print também)
mod.info()                           -- {id,name,version,author,description}
mod.read("dados/arquivo.json")       -- lê um texto de dentro do seu pacote
storage.set("k", valor)              -- dados salvos entre sessões (números, textos, tabelas)
storage.get("k", padrao)  storage.delete("k")
require("util")                      -- carrega util.lua / util/init.lua do pacote
```

## JavaScript
Mesma API. Aceita `world.get_block` **e** `world.getBlock`. Há `console.log` e `require("./util")` (usa `module.exports`).
Engine: Rhino interpretado, ES6 parcial (let/const, arrow functions, template strings). Sem acesso a classes Java.
`on_use` & cia. são chaves normais do objeto: `{ on_use: function (e) {...} }`.

## Segurança e erros
- Cada mod roda isolado, **sem** acesso a arquivos, rede, `io`, `os.execute` ou classes Java (Lua: também sem `coroutine`).
- Cada chamada tem limite de ~250 ms (loop infinito é interrompido).
- Erros aparecem em vermelho no painel do mod. Com 5 erros em tempo de execução o mod é **pausado**.
- Excluir um mod mantém os IDs dos blocos reservados: se você reinstalar, o mundo continua certo.

## Dica: cooldown em `on_use`
Segurar o botão de usar repete a ação ~4x/s. Use `os.clock()` (Lua) ou `Date.now()` (JS) para limitar.

## Mods e multijogador
- O anfitrião e o visitante precisam ter **os mesmos mods ativados** (a entrada é recusada se os blocos/itens diferirem).
- Os scripts rodam **em cada aparelho**. Mudanças de bloco feitas por script (`world.set_block`, `world.explode`...) são enviadas pela rede como qualquer edição, mas vida, fome, inventário e itens soltos continuam locais. Evite lógica que dependa de estado só de um aparelho.
- Aplicar mudanças nos mods reinicia o jogo e **encerra** a partida em rede.

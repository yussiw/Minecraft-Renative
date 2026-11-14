# Multijogador por Wi-Fi (Blocos 0.4)

## Como jogar
Os dois aparelhos precisam estar na **mesma rede Wi-Fi** (ou um deles ligado ao
ponto de acesso/hotspot do outro).

**Aparelho 1 (anfitrião)**
1. Abra o jogo no seu mundo → menu (ESC) → **Multijogador (Wi-Fi)...**
2. Toque em **Hospedar minha partida**. A tela mostra o IP do aparelho.

**Aparelho 2 (visitante)**
1. Menu → **Multijogador (Wi-Fi)...** → **Procurar partidas na rede...**
2. Toque no nome do anfitrião quando ele aparecer.
   Se não aparecer, use **Entrar digitando o IP...** (IP mostrado no aparelho 1).

O visitante entra no mundo do anfitrião (com o inventário que já tinha) e aparece
na posição do anfitrião. Para sair: Menu → Multijogador → **Sair da partida**
(volta ao seu mundo). O anfitrião usa **Parar de hospedar**.

## O que é sincronizado
- Blocos colocados/quebrados (nos dois sentidos, em tempo real; chunks já editados
  chegam ao entrar)
- Posição, olhar, agachar, golpe e item na mão (20x/s), com o nome sobre a cabeça
- Hora do dia (segue o anfitrião)

## Limites
- Itens soltos no chão, vida/fome e modo de jogo são locais de cada jogador.
- O mundo salvo é o do anfitrião; o visitante salva só o próprio jogador (mp_player.dat).
- Os dois aparelhos precisam ter os **mesmos mods**; senão a entrada é recusada.
- Até 8 jogadores. Portas usadas: TCP 25566 (jogo) e UDP 25567 (descoberta).
- Redes com "isolamento de clientes" (algumas redes públicas/de convidados) bloqueiam
  a conexão: nesse caso use o hotspot de um dos celulares.

## Código
- `Net.kt` – rede (servidor/cliente TCP, descoberta UDP, protocolo, jogadores remotos)
- `World.kt` – gancho `onLocalSet` / `setRemote` para as edições de blocos
- `Game.kt` – `Net.tick` a cada frame e `teleport`
- `GameRenderer.kt` – desenho dos outros jogadores e posição dos nomes
- `HudView.kt` / `MainActivity.kt` – telas de multijogador e ciclo de vida

# Criando mods para o Horizon

A aba **Criar um mod** do Horizon Translator gera mods para o [Horizon](https://github.com/horizon-rust/horizon), a recriação
do Minecraft Java em Rust. Você pode criar mods sem escrever nenhuma linha de código, e pode inspecionar o código-fonte depois para ir além.

- **Sem código:** selecione o que seu mod faz, preencha os campos e clique em **Criar mod**. Você recebe um arquivo `.hnmod` pronto para jogar.
- **Com código:** cada mod acompanha seu código em Rust, um ambiente de testes e um botão de checagem. Use como ponto de partida.
- **Duradouro:** mods feitos no kit usam apenas as partes da API que a equipe do Horizon mantém estáveis para mods (os "pontos de fixação" ou hook points),
  permitindo que o inicializador faça o build mesmo em versões mais recentes do Horizon. Você não precisa refazer o mod a cada atualização.
- **Atualizações automáticas:** o app verifica no GitHub se há novas versões ao iniciar e avisa você. Novas versões adicionam novos
  recursos ao construtor visual e ao guia.

## Início rápido

1. Baixe o Horizon Translator para Windows ou Linux pelo site (ou pela página de Releases no GitHub) e abra-o.
2. Acesse a aba **Criar um mod**. Escolha um modelo em **Começar de** e clique em **Usar**, ou comece com o modelo em branco.
3. Dê um **nome** ao seu mod. O **id do mod** é preenchido automaticamente: mantenha o mesmo em todas as versões futuras do mesmo mod.
4. Marque as funções desejadas (caixa de HUD, comandos de chat, teclas, zoom, mensagens de chat) e preencha as opções.
5. Deixe a opção **Adicionar ao Horizon** marcada e clique em **Criar mod**.
6. Na página de **Mods** do inicializador do Horizon: clique em **Build** sob "Native build of Horizon" (se a opção não aparecer, ative
   **Settings > Game > Native mods** primeiro). O primeiro build baixa o Rust e
   leva de 20 a 30 minutos; builds seguintes levam apenas alguns minutos.
7. Jogue. Digite `/<id do seu mod>` no chat para ver a lista de comandos e teclas do seu mod.

Seu mod é salvo em `Documents/Horizon Translator/<mod id>/` (você pode alterar essa pasta na parte inferior do aplicativo).

## O que você pode criar sem código

### Caixa de HUD

Texto em um canto da tela, como suas coordenadas, a direção para onde você olha ou seus FPS. Cada linha pode mesclar texto comum
com variáveis (veja abaixo). A caixa é ocultada ao apertar F1 e desvia da tela de depuração F3. Escolha uma cor de texto, um
fundo escuro de contraste ou não, e uma tecla para exibir ou ocultar. Os jogadores também podem desativá-la em **Opções > Mods**.

### Comandos de chat

Comandos que os jogadores digitam no chat, como `/hello`. Um comando pode:

- **Dizer no chat:** exibir uma ou várias linhas (use `|` para separar linhas na caixa).
- **Exibir / ocultar a caixa de HUD.**
- **Aproximar / afastar zoom.**

Os comandos rodam no próprio cliente do jogador, antes que o servidor os receba, funcionando em qualquer servidor. Não reutilize
nomes de comandos padrão de servidores (`/help`, `/home`, `/spawn`...): seu mod tomaria o controle deles. `/<mod id>` é sempre adicionado: ele
lista seus comandos e teclas configuradas.

### Teclas

Teclas que realizam as mesmas ações dos comandos. Os jogadores podem alterar cada tecla em **Opções > Mods > seu mod**. As teclas funcionam
apenas durante a partida, e não enquanto você digita no chat ou estiver com um menu aberto.

### Zoom

Uma tecla de zoom similar à do OptiFine: nível de aproximação (de 1.5 a 10), funcionando apenas enquanto pressionada ou no modo liga/desliga, suave como
a luneta, com a roda do mouse ajustando a distância durante o zoom. A sensibilidade do mouse reduz suavemente enquanto o zoom estiver ativo.

### Mensagens de chat

- **Mensagem de boas-vindas:** linhas exibidas quando você entra em um mundo.
- **Mensagens temporizadas:** avisos periódicos a cada X minutos durante a partida (contados a partir da entrada no mundo).

As mensagens aparecem apenas na sua tela local; nada é transmitido para o servidor.

### Variáveis

Qualquer texto pode conter essas variáveis. Elas são substituídas no momento em que o texto é desenhado.

| Variável | Exibe |
|---|---|
| `{x}` `{y}` `{z}` | Sua posição atual, com uma casa decimal: `12.3` |
| `{bx}` `{by}` `{bz}` | O bloco exato em que você se encontra: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Para onde você está olhando, em graus |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Quadros por segundo |
| `{name}` `{version}` | O nome e a versão do seu mod |

### Configurações que os jogadores recebem

Cada mod do kit adiciona uma linha em **Opções > Mods** com suas teclas e botões LIGA/DESLIGA para cada componente (caixa de HUD, zoom, mensagem
de boas-vindas, mensagens periódicas). Os jogadores também podem desligar o mod inteiro por ali.

## Criando uma nova versão

1. **Abrir um mod que criei...** e selecione a pasta do mod. O formulário será preenchido com suas escolhas anteriores.
2. Faça as modificações que desejar e aumente a **versão** (1.0.0 → 1.1.0).
3. **Criar mod**. A nova versão é salva em uma pasta nova (`<mod id>-2`...), sem jamais sobrescrever a antiga, e substitui o
   `.hnmod` anterior no Horizon. Faça o build novamente pelo inicializador.

Mantenha o mesmo **id do mod**: o Horizon reconhece o mod pelo id, preservando as configurações e teclas salvas pelos jogadores.

## Compartilhando seu mod

Envie o arquivo `.hnmod`. Os jogadores o instalam pela página Mods do inicializador (ou copiam para
`%APPDATA%\horizon\horizon-native-mods\` no Windows, `~/.local/share/horizon/horizon-native-mods/` no Linux) e compilam.

Mods nativos são compilados no próprio jogo e executam com os privilégios do usuário; não rodam em sandbox isolada. Por esse motivo o inicializador
solicita autorização antes de compilar. Compartilhe apenas mods cujo código você conheça, e instale somente mods de pessoas confiáveis.

## Indo além: o código-fonte

Cada pasta de mod é um projeto Rust completo:

| Arquivo | O que representa |
|---|---|
| `mixins/<id>/runtime.rs` | O mod em si. Suas configurações no topo (textos, teclas, comandos), a lógica no meio e testes ao final |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Os hooks: cada um roda quando o Horizon o chama (uma tecla, o HUD, um comando, um tick) |
| `mixins/<id>/horizon-mixin.toml` | Define qual ponto de fixação aciona cada arquivo |
| `Cargo.toml` | Permite rodar `cargo test` para testar o `runtime.rs` sem precisar abrir o jogo |
| `compatibility.json` | A versão dos pontos de fixação necessária e o commit do Horizon no qual o mod foi construído |
| `horizon-kit.json` | Suas configurações escolhidas no construtor |
| `<id>.hnmod` | O mod empacotado |

Para alterar o código:

1. Edite os arquivos no seu editor favorito (VS Code com a extensão rust-analyzer é uma excelente escolha).
2. No app: **Criar um mod > Verificar e empacotar uma pasta…**, e escolha a pasta do seu mod. Com o Rust instalado
   (rustup.rs), ele compila seu código junto aos módulos do Horizon e roda seus testes primeiro; sem o Rust ele empacota
   imediatamente e o compilador do inicializador apontará eventuais erros. Em seguida, gera um novo `.hnmod` e o adiciona ao Horizon.
3. Faça o build no inicializador e teste no jogo.

**Não use Criar mod em um projeto modificado manualmente:** isso recriaria os arquivos a partir do formulário. Use Verificar e empacotar.

### Pela linha de comando (e para agentes de IA)

O arquivo `horizon-port.jar` do app permite executar os mesmos comandos pelo terminal, com verificações extras. Toda pasta de mod inclui
um `AGENTS.md` com esses comandos para agentes autônomos de IA, e um `HOOKS.md` detalhando cada ponto de fixação e utilitário.

| Comando | O que faz |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Cria um mod a partir de uma especificação (`kit-examples/` contém exemplos) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Lista os pontos de fixação e utilitários do Horizon, com o `[[injection]]` de cada hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Checagem completa: insere o mod em uma cópia do Horizon e compila tudo, como o launcher faz |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Checagem completa, testes, empacotamento e adição ao Horizon |

`SRC` é o código-fonte do Horizon, descompactado para o comando `compile`. O `compile` necessita do Rust e do utilitário horizon-mixins do launcher (instalado
no primeiro build nativo); sua execução inicial requer alguns GB de download e vários minutos. Ao contrário de **Verificar e empacotar**, ele também valida
códigos que leem dados internos do motor (`game.player`, `game.items()`...), que não fazem parte da API pública de hooks.

### Como funciona um hook

O Horizon executa funções vazias em pontos pré-determinados (os **pontos de fixação** ou hook points). Cada declaração `[[injection]]` no `horizon-mixin.toml`
injeta um dos seus arquivos no início de uma dessas funções. Seu arquivo contém uma função que recebe os argumentos
definidos na seção `bindings`. Por exemplo, `key.rs` recebe o motor do jogo, o nome da tecla e o estado (se foi pressionada ou solta):

```rust
fn my_mod_key(game: &mut crate::game::Game<'_>, key: &str, pressed: bool) {
    use crate::game::kit_my_mod as m;
    if !game.native_on(m::ID) {
        return;
    }
    if pressed && game.native_playing() && key == game.native_mod_key(m::ID, "jump_note", "key.keyboard.j") {
        game.native_chat("You pressed J");
    }
}
```

O `runtime.rs` é inserido no jogo como um módulo (`crate::game::kit_<id>`) e atua simultaneamente como biblioteca própria do projeto, de forma
que qualquer lógica independente do motor (cálculos, manipulação de texto, estados internos) pode ser testada com `cargo test`.

Regras do horizon-mixins: não use comentários `//!` nem atributos `#![...]` no `runtime.rs`, não crie arquivos `mod x;` e não use
`include!`. Mantenha tudo nesse arquivo único (módulos inline `mod x { ... }` são permitidos).

### Os pontos de fixação (hook points)

Estes são os blocos de construção para mods do kit. Seus nomes e parâmetros continuam consistentes entre as diferentes versões do Horizon.

| Ponto de fixação | Quando o Horizon executa | O que ele recebe |
|---|---|---|
| `native_frame` | A cada quadro (frame), dentro de um mundo ou não | `elapsed: Duration` |
| `native_tick` | A cada tick do jogo (20 por segundo), inclusive no servidor local: consulte `native_window()` | |
| `native_hud` | Durante a renderização do HUD (exceto sob F1) | `batch`, `corners`: posicione a caixa com `corners.place(corner, w, h)` |
| `native_key` | Quando uma tecla ou botão do mouse é acionado ou liberado (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Roda do mouse durante o jogo | `steps`, `claimed`: ative para impedir que o scroll altere a barra rápida |
| `native_command` | Um comando de chat, sem a barra, antes do envio ao servidor | `command`, `ran`: marque como verdadeiro quando processado pelo seu mod |
| `native_zoom` | A cada quadro durante o jogo | `zoom: &mut Option<NativeZoom>`: configure para aplicar aproximação |
| `native_minimap` | A cada quadro | `minimap: &mut Option<NativeMinimap>`: configure para exibir o minimapa do Horizon |
| `native_mods` | Opções > Mods lista os mods ativos | `mods`: adiciona a linha e atalhos do seu mod |
| `native_settings` | Ao abrir a tela de configurações do seu mod | `mod_id`, `buttons`: adiciona os textos dos botões |
| `native_settings_click` | Ao clicar em um botão na página de configurações | `mod_id`, `button` |

Cada hook recebe `game` quando sua seção `bindings` requisitar: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }`
(`&crate::game::Game<'_>` e `&*self` para `native_zoom` e `native_minimap`). Os arquivos criados pelo kit exibem as
`bindings` de cada hook.

### Utilitários do jogo

Chame estas funções no objeto `game` a partir de qualquer hook.

| Utilitário | O que faz |
|---|---|
| `native_on(id)` | Verifica se o jogador manteve seu mod ativado em Opções > Mods. Verifique sempre no início de cada hook |
| `native_playing()` | Indica se está em jogo, sem nenhum menu, chat ou placa aberta |
| `native_window()` | Confirma se é o cliente visual do jogo, e não um servidor dedicado |
| `native_player()` | Dados do jogador no mundo: `x`, `y`, `z`, `yaw`, `pitch`, `dimension`, ou `None` |
| `native_frame_rate()` | FPS atual, o quadro mais lento e o percentil 1% mais baixo |
| `native_clicks()` | Cliques de ataque e uso registrados no último segundo |
| `native_mapping("key.jump")` | Indica se um controle original está pressionado e o nome da tecla associada |
| `native_mod_key(id, name, default)` | A tecla que o jogador configurou para o atalho `name` |
| `native_chat(text)` | Envia uma linha de mensagem no chat do jogador |
| `native_text(batch, text, [x, y], rgb, shadow)` | Renderiza texto no HUD |
| `native_fill(batch, [x, y, w, h], argb)` | Desenha um retângulo preenchido no HUD |
| `native_text_width(text)` | Largura do texto em pixels da interface (GUI) |
| `native_corner_shown(corner)` | Se determinado canto está livre (a tela F3 ocupa os superiores) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Lê e persiste configurações salvas do seu mod |
| `native_data_dir(id)` | Pasta exclusiva do seu mod, para guardar arquivos próprios |

Versões futuras do Horizon incluirão mais recursos (painel lateral de itens em inventários, receitas, concessão de itens no modo criativo). Mods que
usarem tais utilitários exigirão a respectiva versão do Horizon; o comando **Verificar e empacotar** detecta e registra essa dependência automaticamente.

### Ideias para explorar

- Um **contador de CPS**: uma linha no HUD chamando `native_clicks()`.
- Exibição de **teclas pressionadas (keystrokes)**: caixas que acendem ao segurar `native_mapping("key.forward")` e outros controles.
- Aviso de **coordenadas de morte**: salve `native_player()` a cada tick e mostre onde você estava quando o valor passar a ser `None`.
- Uma **calculadora no chat**: `/calc 2+2`, processando os parâmetros enviados após o nome do comando.
- Um **cronômetro de sessão**: conte os ticks em `native_tick` e exiba o tempo decorrido no HUD.

## O que mods ainda não podem fazer

O Horizon disponibiliza para mods nativos os pontos de fixação acima, e não o acesso irrestrito ao jogo completo. Mods ainda não conseguem:

- adicionar blocos, itens, criaturas (mobs), receitas ou texturas (estes são content mods, `.hmod`, não suportados por este kit);
- modificar o comportamento do mundo ou das criaturas em servidores dedicados;
- ler blocos ou entidades ao redor do jogador ou vasculhar o inventário do jogador;
- enviar mensagens ou comandos diretamente para o servidor.

Conforme novos pontos de fixação forem adicionados ao Horizon, o kit os adotará: novas atualizações do app disponibilizarão os recursos no construtor.

## Solução de problemas

- **O build do inicializador falhou:** a página Mods exibe as últimas linhas do erro. Execute **Verificar e empacotar** com o Rust
  instalado para ver o diagnóstico completo com arquivo e linha exatos. Abra uma issue no GitHub se precisar de ajuda.
- **"Não empacotado: TODO"**: o empacotador rejeita códigos contendo a palavra `TODO` (marcação padrão em mods convertidos incompletos).
  Implemente ou remova essas linhas.
- **Minha tecla não responde:** confira em **Opções > Mods > seu mod**: outro mod ou o próprio Horizon podem estar usando a mesma tecla.
- **Meu comando vai para o servidor:** identificadores de mod e nomes de comandos devem usar apenas letras minúsculas; verifique se `/<mod id>` o lista corretamente.

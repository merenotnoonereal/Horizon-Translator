# Créer des mods Horizon

L'onglet **Créer un mod** d'Horizon Translator permet de concevoir des mods pour [Horizon](https://github.com/horizon-rust/horizon), la
réécriture en Rust de Minecraft Java. Tu peux créer un mod sans écrire la moindre ligne de code, et ouvrir son code source plus tard pour aller plus loin.

- **Sans code :** coche ce que fait ton mod, remplis les champs, clique sur **Créer le mod**. Tu obtiens un fichier `.hnmod` prêt à l'emploi.
- **Avec code :** chaque mod est fourni avec son code source Rust, une configuration de test et un bouton de vérification. Utilise-le comme point de départ.
- **Pérenne :** les mods du kit utilisent uniquement les parties d'Horizon que l'équipe maintient stables pour les mods (les « points d'ancrage » ou hook points),
  le lanceur les compile donc également sur les versions plus récentes d'Horizon. Tu n'as pas besoin d'une nouvelle version pour chaque mise à jour d'Horizon.
- **Mises à jour automatiques :** l'application vérifie sur GitHub si une nouvelle version est disponible au démarrage et te prévient. Les nouvelles versions apportent
  de nouvelles fonctionnalités au créateur visuel et au guide.

## Démarrage rapide

1. Télécharge Horizon Translator pour Windows ou Linux depuis le site web (ou la page GitHub Releases) et ouvre-le.
2. Ouvre l'onglet **Créer un mod**. Choisis un exemple sous **Commencer depuis** et clique sur **Utiliser**, ou démarre d'un mod vierge.
3. Donne un **nom** à ton mod. L'**identifiant du mod** se remplit automatiquement : conserve-le à l'identique pour chaque nouvelle version du même mod.
4. Coche les éléments souhaités (cadre HUD, commandes de tchat, touches, zoom, messages de tchat) et remplis-les.
5. Laisse cochée la case **L'ajouter à Horizon** et clique sur **Créer le mod**.
6. Sur la page **Mods** du lanceur Horizon : clique sur **Build** sous « Native build of Horizon » (si elle n'apparaît pas, active d'abord
   **Settings > Game > Native mods**). La première compilation télécharge Rust et
   prend de 20 à 30 minutes ; les compilations suivantes ne prennent que quelques minutes.
7. Joue. Tape `/<l'identifiant de ton mod>` dans le tchat pour afficher les commandes et les touches de ton mod.

Ton mod est enregistré dans `Documents/Horizon Translator/<mod id>/` (tu peux changer ce dossier en bas de l'application).

## Ce que tu peux créer sans coder

### Cadre HUD

Du texte dans un coin de l'écran, comme tes coordonnées, ton orientation ou tes FPS. Chaque ligne peut mélanger du texte brut
avec des variables (voir ci-dessous). Le cadre se masque avec F1 et s'écarte de l'écran F3. Choisis une couleur de texte, un
fond sombre derrière ou non, et une touche pour l'afficher ou le masquer. Les joueurs peuvent aussi le désactiver dans **Options > Mods**.

### Commandes de tchat

Des commandes que les joueurs tapent dans le tchat, comme `/hello`. Une commande peut :

- **Dire dans le tchat :** afficher une ou plusieurs lignes (utilise `|` entre les lignes dans le champ).
- **Afficher / masquer le cadre HUD.**
- **Zoom avant / arrière.**

Les commandes s'exécutent sur le client du joueur, avant que le serveur ne les reçoive, elles fonctionnent donc sur n'importe quel serveur. Ne réutilise pas
le nom d'une commande du serveur (`/help`, `/home`, `/spawn`...) : ton mod l'intercepterait. `/<mod id>` est toujours ajouté : il
liste tes commandes et touches.

### Touches

Des touches qui effectuent les mêmes actions que les commandes. Les joueurs peuvent modifier chaque touche dans **Options > Mods > ton mod**. Les touches n'agissent
que pendant que tu joues, pas quand tu écris dans le tchat ni quand un menu est ouvert.

### Zoom

Une touche de zoom comme celle d'OptiFine : niveau de rapprochement (de 1.5 à 10), uniquement maintenue ou bascule on/off, fluide comme
la longue-vue, avec la molette de la souris qui zoome plus ou moins fort pendant le zoom. La rotation de la vue à la souris ralentit pendant le zoom.

### Messages de tchat

- **Message de bienvenue :** lignes affichées lorsque tu rejoins un monde.
- **Messages programmés :** lignes affichées toutes les X minutes pendant que tu joues (comptées à partir de la connexion au monde).

Les messages s'affichent uniquement sur ton écran ; rien n'est envoyé au serveur.

### Variables

Tout texte peut en contenir. Elles sont remplacées dynamiquement lors de l'affichage du texte.

| Variable | Affiche |
|---|---|
| `{x}` `{y}` `{z}` | Ta position, une décimale : `12.3` |
| `{bx}` `{by}` `{bz}` | Le bloc dans lequel tu te trouves : `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Là où tu regardes, en degrés |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Images par seconde |
| `{name}` `{version}` | Le nom et la version de ton mod |

### Réglages disponibles pour les joueurs

Chaque mod du kit possède une ligne dans **Options > Mods** avec ses touches et un bouton ON/OFF pour chaque fonctionnalité (cadre HUD, zoom, message
de bienvenue, messages programmés). Les joueurs peuvent également y désactiver le mod complet.

## Créer une nouvelle version

1. **Ouvrir un mod que j'ai créé...** et sélectionne le dossier de ton mod. Le formulaire se préremplit avec tes choix précédents.
2. Modifie ce que tu souhaites et incrémente la **version** (1.0.0 → 1.1.0).
3. **Créer le mod**. La nouvelle version va dans un nouveau dossier (`<mod id>-2`...), n'écrase jamais l'ancienne, et remplace
   l'ancien `.hnmod` dans Horizon. Compile à nouveau dans le lanceur.

Conserve le même **identifiant de mod** : Horizon reconnaît un mod grâce à son identifiant, et les réglages ainsi que les touches des joueurs sont préservés.

## Partager ton mod

Envoie le fichier `.hnmod`. Les joueurs l'installent depuis la page Mods du lanceur (ou le copient dans
`%APPDATA%\horizon\horizon-native-mods\` sous Windows, `~/.local/share/horizon/horizon-native-mods/` sous Linux) et compilent.

Les mods natifs sont compilés dans le jeu et s'exécutent avec les privilèges du joueur ; ils ne sont pas cloisonnés dans un bac à sable. C'est pourquoi le lanceur
demande confirmation avant de compiler. Ne partage que des mods dont tu connais le code, et n'installe que des mods provenant de personnes de confiance.

## Pour aller plus loin : le code

Chaque dossier de mod est un petit projet Rust :

| Fichier | Ce que c'est |
|---|---|
| `mixins/<id>/runtime.rs` | Le mod. Ses réglages en haut (textes, touches, commandes), sa logique, et ses tests en bas |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | Les hooks : chacun s'exécute quand Horizon l'appelle (une touche, le HUD, une commande, un tick) |
| `mixins/<id>/horizon-mixin.toml` | Quel point d'ancrage exécute quel fichier |
| `Cargo.toml` | Permet à `cargo test` d'exécuter les tests dans `runtime.rs` sans lancer le jeu |
| `compatibility.json` | La version des points d'ancrage requise par le mod et le commit Horizon sur lequel il a été conçu |
| `horizon-kit.json` | Tes sélections dans le générateur visuel |
| `<id>.hnmod` | Le mod empaqueté |

Pour modifier le code :

1. Édite les fichiers dans n'importe quel éditeur (VS Code avec l'extension rust-analyzer est un très bon choix).
2. Dans l'application : **Créer un mod > Vérifier et empaqueter un dossier...**, et sélectionne le dossier de ton mod. Avec Rust installé
   (rustup.rs), il compile ton code avec les utilitaires d'Horizon et lance tes tests d'abord ; sans Rust, il empaquette
   directement et la compilation du lanceur t'indiquera les erreurs éventuelles. Il produit ensuite un nouveau `.hnmod` et l'ajoute à Horizon.
3. Compile dans le lanceur et teste-le.

**N'utilise pas Créer le mod sur un mod dont tu as modifié le code à la main :** cela écraserait tes changements depuis le formulaire. Utilise Vérifier et empaqueter.

### En ligne de commande (et pour les agents IA)

L'exécutable `horizon-port.jar` de l'application propose les mêmes opérations dans un terminal, avec une vérification complète en plus. Chaque dossier de mod contient
un fichier `AGENTS.md` décrivant ces commandes pour les agents de codage IA, et un `HOOKS.md` listant l'ensemble des points d'ancrage et utilitaires.

| Commande | Ce qu'elle fait |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Génère un mod à partir d'une spécification du constructeur (`kit-examples/` en fournit) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Liste les points d'ancrage et utilitaires d'Horizon, avec l'`[[injection]]` de chaque hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | Vérification complète : intègre le mod dans une copie d'Horizon et le compile, comme le fait le lanceur |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Vérification complète, tests, empaquetage et ajout à Horizon |

`SRC` correspond aux sources d'Horizon, décompressées pour `compile`. `compile` requiert Rust et l'outil horizon-mixins du lanceur (installé
lors de son premier build natif) ; son premier lancement télécharge plusieurs Go et prend plusieurs minutes. Contrairement à **Vérifier et empaqueter**, il vérifie
aussi le code qui accède aux données internes du jeu (`game.player`, `game.items()`...), qui ne font pas partie des points d'ancrage.

### Comment fonctionne un hook

Horizon appelle des fonctions vides à des endroits précis (les **points d'ancrage** ou hook points). Chaque ligne `[[injection]]` dans `horizon-mixin.toml`
place l'un de tes fichiers au début de l'une de ces fonctions. Ton fichier contient une fonction qui reçoit les arguments
définis sous `bindings`. Par exemple, `key.rs` reçoit le jeu, le nom de la touche et si elle a été pressée ou relâchée :

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

`runtime.rs` est ajouté au jeu en tant que module (`crate::game::kit_<id>`) et constitue également la bibliothèque autonome du projet, donc
tout ce qui ne dépend pas du jeu (calculs, textes, état interne du mod) y prend place et se teste via `cargo test`.

Règles imposées par horizon-mixins : pas de commentaires `//!` ni d'attributs `#![...]` dans `runtime.rs`, aucun fichier `mod x;` et pas de
`include!`. Rassemble tout dans ce fichier unique (les blocs en ligne `mod x { ... }` sont autorisés).

### Les points d'ancrage (hook points)

Ce sont les composants de base des mods du kit. Leurs noms et arguments demeurent identiques d'une version d'Horizon à l'autre.

| Point d'ancrage | Quand Horizon l'appelle | Ce qu'il reçoit |
|---|---|---|
| `native_frame` | À chaque image (frame), dans un monde ou non | `elapsed: Duration` |
| `native_tick` | À chaque tick de jeu (20 par seconde), y compris sur le serveur solo : vérifier `native_window()` | |
| `native_hud` | Pendant le dessin du HUD (pas sous F1) | `batch`, `corners` : positionner un cadre avec `corners.place(corner, w, h)` |
| `native_key` | Une touche ou un bouton de souris est pressé ou relâché (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Molette de la souris en jeu | `steps`, `claimed` : l'activer pour empêcher la molette de faire défiler la barre rapide |
| `native_command` | Une commande de tchat, sans son slash, avant transmission au serveur | `command`, `ran` : l'activer lorsque la commande appartenait à ton mod |
| `native_zoom` | À chaque image en jeu | `zoom: &mut Option<NativeZoom>` : le définir pour appliquer le zoom |
| `native_minimap` | À chaque image | `minimap: &mut Option<NativeMinimap>` : le définir pour afficher la minicarte d'Horizon |
| `native_mods` | Options > Mods affiche la liste des mods | `mods` : ajouter la ligne et les raccourcis de ton mod |
| `native_settings` | Ouverture de la page des réglages de ton mod | `mod_id`, `buttons` : ajouter les libellés des boutons |
| `native_settings_click` | Clic sur un bouton de la page de tes réglages | `mod_id`, `button` |

Chaque hook reçoit `game` quand ses `bindings` le requièrent : `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }`
(`&crate::game::Game<'_>` et `&*self` pour `native_zoom` et `native_minimap`). Les fichiers générés par le kit affichent les
`bindings` de chacun.

### Les fonctions utilitaires du jeu

Appelle ces fonctions sur `game` depuis n'importe quel hook.

| Fonction utilitaire | Ce qu'elle fait |
|---|---|
| `native_on(id)` | Indique si le joueur a activé ton mod dans Options > Mods. À vérifier en premier dans chaque hook |
| `native_playing()` | En jeu, sans menu, tchat ni panneau ouvert |
| `native_window()` | Indique qu'il s'agit du jeu avec fenêtre graphique, pas d'un serveur dédié |
| `native_player()` | Le joueur dans le monde : `x`, `y`, `z`, `yaw`, `pitch`, `dimension`, ou `None` |
| `native_frame_rate()` | FPS actuels, l'image la plus lente et le percentile 1 % le plus bas |
| `native_clicks()` | Clics d'attaque et d'utilisation enregistrés lors de la dernière seconde |
| `native_mapping("key.jump")` | Indique si une commande vanilla est maintenue, avec le nom de sa touche |
| `native_mod_key(id, name, default)` | La touche que le joueur a assignée à ton action `name` |
| `native_chat(text)` | Affiche une ligne dans le tchat du joueur |
| `native_text(batch, text, [x, y], rgb, shadow)` | Dessine du texte sur le HUD |
| `native_fill(batch, [x, y, w, h], argb)` | Dessine un rectangle plein sur le HUD |
| `native_text_width(text)` | Calcule la largeur d'un texte en pixels GUI |
| `native_corner_shown(corner)` | Indique si un coin est libre (l'écran F3 occupe ceux du haut) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Lit et conserve les paramètres de ton mod |
| `native_data_dir(id)` | Dossier propre à ton mod, pour ses fichiers locaux |

Les versions plus récentes d'Horizon en ajoutent d'autres (panneau d'objets à côté des inventaires, recettes, don d'objets en créatif). Un mod qui les
exploite nécessite cette version d'Horizon ; **Vérifier et empaqueter** le détecte et l'inscrit dans le mod.

### Idées à expérimenter

- Un **compteur de CPS** : une ligne dans le HUD appelant `native_clicks()`.
- Un affichage des **touches pressées (keystrokes)** : des cases qui s'illuminent lorsque `native_mapping("key.forward")` et les autres sont maintenues.
- Une notification des **coordonnées de mort** : mémorise `native_player()` à chaque tick et annonce où tu te trouvais quand elle devient `None`.
- Une **calculatrice** : `/calc 2+2`, en lisant les paramètres après le nom de la commande.
- Un **chronomètre de session** : compte les ticks dans `native_tick` et affiche le temps passé dans le cadre HUD.

## Ce que les mods ne peuvent pas encore faire

Horizon met à disposition des mods natifs les points d'ancrage ci-dessus, et non l'intégralité du moteur. Les mods ne peuvent pas encore :

- ajouter des blocs, des objets, des créatures (mobs), des recettes ou des textures (ce sont des content mods, `.hmod`, que ce kit ne produit pas) ;
- modifier le comportement de l'environnement ou des créatures sur un serveur ;
- inspecter l'environnement autour du joueur (blocs, créatures, objets au sol) ou l'inventaire du joueur ;
- envoyer des messages de tchat ou des commandes au serveur.

Au fil des ajouts de points d'ancrage par Horizon, le kit les prendra en charge : les mises à jour de l'application les intégreront au constructeur visuel.

## En cas de problème

- **La compilation du lanceur échoue :** sa page Mods affiche les dernières lignes de l'erreur. Lance **Vérifier et empaqueter** avec Rust
  installé pour obtenir le message complet avec fichier et numéro de ligne. Copie l'erreur dans un ticket GitHub en cas de blocage.
- **« Non empaqueté : TODO »** : l'empaqueteur rejette tout code contenant `TODO` (c'est ainsi que sont balisés les mods convertis inachevés).
  Complète ou supprime ces lignes.
- **Ma touche ne fait rien :** vérifie dans **Options > Mods > ton mod** : un autre mod ou Horizon lui-même utilise peut-être la même touche.
- **Ma commande part vers le serveur :** les identifiants de mod et les noms de commande doivent être en minuscules ; vérifie que `/<mod id>` la mentionne bien.

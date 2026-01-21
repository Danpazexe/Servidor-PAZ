# Guia de Configuração: Servidor Vanilla Anarchy (SG)

Este guia documenta todas as alterações feitas para transformar o servidor Paper 1.20.1 em uma experiência **Vanilla/Anarchy** autêntica (estilo SG), removendo otimizações agressivas e permitindo exploits técnicos.

## 1. Configurações do Servidor

### `server.properties`
Customização visual do servidor.

```properties
motd=\u00A73\u00A7lSERVIDOR DE TESTE \u00A78- \u00A7b\u00A7lPAZ\n\u00A77Experi\u00AAncia Vanilla Sem Limites
```

### `config/paper-global.yml`
Habilita exploits técnicos clássicos e desativa limitadores de pacotes (anti-spam/kick) que podem atrapalhar PvP ou farms intensas.

```yaml
unsupported-settings:
  allow-grindstone-overstacking: true
  allow-headless-pistons: true        # Permite pistões sem cabeça (quebra de blocos técnicos)
  allow-permanent-block-break-exploits: true # Permite remover Bedrock
  allow-piston-duplication: true      # Permite dupar TNT e Areia
  compression-format: ZLIB
  perform-username-validation: true

packet-limiter:
  all-packets:
    action: DROP # Alterar de KICK para DROP ou aumentar limites
    max-packet-rate: 5000.0 # Aumentar drásticamente ou desativar limitações
```

### `config/paper-world-defaults.yml`
Reverte correções de bugs, ajusta colisões, e garante comportamento Vanilla para Slimes e Farms.

```yaml
collisions:
  max-entity-collisions: 24 # Vanilla (era 8 no Paper)

environment:
  nether-ceiling-void-damage-height: disabled # Permite andar no teto do Nether
  optimize-explosions: false # Mantém física de explosão Vanilla (importante para Canhões/Farms)

fixes:
  disable-unloaded-chunk-enderpearl-exploit: false # Permite stasis chambers
  falling-block-height-nerf: disabled
  fix-curing-zombie-villager-discount-exploit: false # Permite stackar descontos
  fix-items-merging-through-walls: false
  prevent-tnt-from-moving-in-water: false
  split-overstacked-loot: false
  tnt-entity-height-nerf: disabled

spawning:
  all-chunks-are-slime-chunks: false # Vanilla behavior (não altera chunks de slime)
  slime-spawn-height: # Alturas Vanilla confirmadas
    surface-biome:
      maximum: 70.0
      minimum: 50.0
    slime-chunk:
      maximum: 40.0
```

### `spigot.yml`
Remove nerfs de spawners e ajusta tracking ranges.

```yaml
world-settings:
  default:
    nerf-spawner-mobs: false # Desabilita nerf de IA em mobs de spawner (Mob Farms vanilla)
    mob-spawn-range: 8 # Padrão Vanilla (garante rates corretas)
    entity-tracking-range:
      players: 128
      animals: 96
      monsters: 96
      misc: 96
      display: 128
      other: 64
```

### `bukkit.yml`
Limites gerais de spawn.

```yaml
spawn-limits:
  monsters: 70
  animals: 10
  water-animals: 5
  water-ambient: 20
  ambient: 15
```

---

## 2. Sistema de Boas-Vindas (Datapack)

Para manter o servidor sem plugins (.jar), utilizamos um **Datapack** customizado para mensagens de boas-vindas.

### Instalação
Copie a pasta `boas_vindas` para `world/datapacks/`.
Estrutura: `world/datapacks/boas_vindas/data/paz/functions/...`

### Funcionalidades
- **Detecta primeira entrada**: Exibe título "BEM VINDO" e mensagem no chat global.
- **Detecta retorno**: Exibe título "BEM VINDO DE VOLTA" e mensagem pessoal.
- **Scoreboards**: Cria scoreboards `leaves` (nativo) e `seen_leaves` (dummy) para comparar se o jogador saiu e voltou.

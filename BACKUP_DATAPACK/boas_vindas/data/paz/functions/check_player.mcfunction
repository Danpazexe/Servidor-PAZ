# Se o jogador não tem pontuação em seen_leaves, defina igual a leaves (primeira entrada ou reload)
execute unless score @s seen_leaves matches 0.. run scoreboard players operation @s seen_leaves = @s leaves

# Se leaves aumentou (ou seja, ele saiu e voltou), mas aqui estamos detectando a VOLTA.
# Espere... a lógica de 'leave_game' conta quando sai.
# Quando o jogador entra, 'leaves' terá incrementado DESDE A ÚLTIMA VEZ que checamos (que foi antes dele sair).
# Então se leaves > seen_leaves, ele saiu e VOLTOU.

execute if score @s leaves > @s seen_leaves run function paz:greet
execute if score @s leaves > @s seen_leaves run scoreboard players operation @s seen_leaves = @s leaves

# Caso especial: Primeira vez no servidor (ou sem dados)
# Vamos usar uma tag para detectar "joined_before"
execute unless entity @s[tag=joined_before] run function paz:greet_first
execute unless entity @s[tag=joined_before] run tag @s add joined_before

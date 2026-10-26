#!/usr/bin/env python3
"""
Builds src/main/resources/shop-items.json: every item the website shop sells, with its creative tab, stack
size and price. Run it again after changing the tables below:

    python3 tools/shop_prices.py path/to/minecraft-textures/dist/textures/manifest/26.2.id.json

How prices are set (in credits per item; the file stores hundredths of a credit):
- The anchor is what players earn: by default 20 credits per hour of active play, plus 10-50 per advancement.
  An hour of play should buy roughly what an hour of play gathers: a few stacks of common blocks, or a
  couple of diamonds. Buying should never be much cheaper than playing for it.
- RAW is a hand-set value for every gathered resource (what it takes to find, mine, farm or kill for it).
- Crafted items cost their ingredients plus a small crafting premium (CRAFT), using the recipe or a pattern
  (stairs, slabs, walls, tools, armour, colours...).
- Things players can't get in survival, or that only make sense with extra data (enchanted books, potions,
  maps, spawn eggs, operator blocks) are not sold.
"""
import json
import math
import re
import sys

CRAFT = 1.08  # crafting premium on top of the ingredients

COLORS = ['white', 'light_gray', 'gray', 'black', 'brown', 'red', 'orange', 'yellow', 'lime', 'green', 'cyan',
          'light_blue', 'blue', 'purple', 'magenta', 'pink']
WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'pale_oak', 'bamboo',
         'crimson', 'warped']

# ---- Gathered resources (credits per item) ----
RAW = {
    # earth & stone
    'dirt': .03, 'coarse_dirt': .04, 'rooted_dirt': .06, 'grass_block': .08, 'podzol': .1, 'mycelium': .25,
    'dirt_path': .08, 'mud': .05, 'clay_ball': .1, 'gravel': .04, 'sand': .04, 'red_sand': .05, 'suspicious_sand': None,
    'suspicious_gravel': None, 'cobblestone': .03, 'stone': .05, 'deepslate': .05, 'cobbled_deepslate': .03,
    'granite': .04, 'diorite': .04, 'andesite': .04, 'tuff': .05, 'calcite': .08, 'dripstone_block': .12,
    'pointed_dripstone': .12, 'netherrack': .02, 'soul_sand': .08, 'soul_soil': .08, 'basalt': .05,
    'blackstone': .05, 'gilded_blackstone': 1.5, 'end_stone': .1, 'obsidian': 1.5, 'crying_obsidian': 2.5,
    'magma_block': .35, 'ice': .1, 'packed_ice': .95, 'blue_ice': 8.6, 'snow_block': .1, 'snowball': .03,
    'snow': .03, 'powder_snow_bucket': 2.2, 'moss_block': .12, 'moss_carpet': .08, 'pale_moss_block': .15,
    'pale_moss_carpet': .1, 'pale_hanging_moss': .1, 'sculk': .3, 'sculk_vein': .1, 'sculk_catalyst': 25,
    'sculk_sensor': 6, 'sculk_shrieker': 12, 'amethyst_shard': .3, 'amethyst_block': 1.25, 'budding_amethyst': None,
    'small_amethyst_bud': .3, 'medium_amethyst_bud': .4, 'large_amethyst_bud': .5, 'amethyst_cluster': .6,
    'glowstone_dust': .3, 'glowstone': 1.25, 'quartz': .4, 'prismarine_shard': .5, 'prismarine_crystals': .6,
    'sponge': 12, 'wet_sponge': 12, 'cobweb': .5, 'bedrock': None, 'reinforced_deepslate': None,
    'end_portal_frame': None, 'mud_bricks': .07, 'packed_mud': .07, 'resin_clump': .4, 'cinnabar': .06, 'sulfur': .06,
    'potent_sulfur': .3, 'sulfur_spike': .15, 'dried_ghast': 25,
    # ores & minerals
    'coal': .4, 'charcoal': .35, 'raw_iron': 1, 'iron_ingot': 1.2, 'iron_nugget': .14, 'raw_gold': 1.6,
    'gold_ingot': 1.8, 'gold_nugget': .2, 'raw_copper': .4, 'copper_ingot': .45, 'copper_nugget': .05,
    'redstone': .3, 'lapis_lazuli': .45, 'emerald': 3, 'diamond': 8, 'netherite_scrap': 35, 'ancient_debris': 35,
    'coal_ore': .8, 'deepslate_coal_ore': .9, 'iron_ore': 1.8, 'deepslate_iron_ore': 2,
    'gold_ore': 2.6, 'deepslate_gold_ore': 2.8, 'copper_ore': 1.2, 'deepslate_copper_ore': 1.3, 'redstone_ore': 1.8,
    'deepslate_redstone_ore': 2, 'lapis_ore': 2.6, 'deepslate_lapis_ore': 2.8, 'diamond_ore': 11,
    'deepslate_diamond_ore': 12, 'emerald_ore': 4.5, 'deepslate_emerald_ore': 5, 'nether_quartz_ore': .9,
    'nether_gold_ore': 1.2,
    # wood & plants
    'bamboo': .03, 'stick': .02, 'sugar_cane': .1, 'cactus': .12, 'kelp': .03, 'dried_kelp': .04, 'vine': .05,
    'weeping_vines': .1, 'twisting_vines': .1, 'cave_vines': .1, 'glow_lichen': .1, 'hanging_roots': .06,
    'lily_pad': .12, 'seagrass': .03, 'sea_pickle': .2, 'big_dripleaf': .3, 'small_dripleaf': .3, 'spore_blossom': 6,
    'azalea': .5, 'flowering_azalea': .6, 'mangrove_roots': .15, 'muddy_mangrove_roots': .15, 'mangrove_propagule': .2,
    'brown_mushroom': .12, 'red_mushroom': .12, 'brown_mushroom_block': .3, 'red_mushroom_block': .3,
    'mushroom_stem': .3, 'crimson_fungus': .2, 'warped_fungus': .2, 'crimson_roots': .1, 'warped_roots': .1,
    'nether_sprouts': .1, 'crimson_nylium': .2, 'warped_nylium': .2, 'nether_wart_block': .9, 'warped_wart_block': .9,
    'shroomlight': 1.5, 'short_grass': .02, 'tall_grass': .04, 'fern': .02, 'large_fern': .04, 'dead_bush': .04,
    'short_dry_grass': .03, 'tall_dry_grass': .05, 'bush': .04, 'firefly_bush': .4, 'leaf_litter': .05,
    'chorus_plant': .3, 'chorus_flower': 3, 'pumpkin': .3, 'carved_pumpkin': .32, 'melon': .5, 'hay_block': .95,
    'honeycomb': .5, 'bee_nest': 15, 'beehive': 7, 'frogspawn': None, 'turtle_egg': 15, 'sniffer_egg': 100,
    'pitcher_pod': 5, 'pitcher_plant': 5, 'torchflower_seeds': 4, 'torchflower': 4, 'cactus_flower': .3,
    'wildflowers': .1, 'pink_petals': .1, 'tube_coral_block': 1.5, 'creaking_heart': 30, 'open_eyeblossom': .3,
    'closed_eyeblossom': .3, 'eyeblossom': .3,
    # farming & food
    'wheat': .1, 'wheat_seeds': .03, 'beetroot': .1, 'beetroot_seeds': .03, 'carrot': .1, 'potato': .1,
    'poisonous_potato': .05, 'melon_seeds': .05, 'pumpkin_seeds': .05, 'melon_slice': .06, 'apple': .3,
    'sweet_berries': .06, 'glow_berries': .12, 'cocoa_beans': .2, 'nether_wart': .2, 'chorus_fruit': .3,
    'egg': .1, 'blue_egg': .12, 'brown_egg': .12, 'sugar': .1, 'beef': .4, 'porkchop': .4, 'chicken': .3,
    'mutton': .3, 'rabbit': .35, 'cod': .2, 'salmon': .3, 'tropical_fish': .5, 'pufferfish': .5,
    'honey_bottle': .6, 'honey_block': 2.6,
    # mob drops
    'string': .12, 'feather': .1, 'leather': .4, 'wool': .2, 'rabbit_hide': .2, 'bone': .15, 'gunpowder': .5,
    'slime_ball': .8, 'ender_pearl': 2.5, 'blaze_rod': 3, 'ghast_tear': 6, 'magma_cream': 1.5, 'spider_eye': .3,
    'rotten_flesh': .02, 'phantom_membrane': 2, 'rabbit_foot': 2, 'ink_sac': .3, 'glow_ink_sac': 1,
    'nautilus_shell': 8, 'heart_of_the_sea': 150, 'shulker_shell': 25, 'dragon_breath': 10, 'echo_shard': 10,
    'turtle_scute': 3, 'armadillo_scute': 2, 'breeze_rod': 5, 'nether_star': 500, 'totem_of_undying': 150,
    'elytra': 600, 'trident': 200, 'heavy_core': 250, 'experience_bottle': .8, 'saddle': 12, 'name_tag': 8,
    'iron_horse_armor': 15, 'golden_horse_armor': 18, 'diamond_horse_armor': 60, 'skeleton_skull': 25,
    'wither_skeleton_skull': 60, 'zombie_head': 30, 'creeper_head': 30, 'piglin_head': 30, 'dragon_head': 500,
    'player_head': None, 'dragon_egg': None, 'enchanted_golden_apple': 250, 'disc_fragment_5': 10, 'trial_key': 8,
    'ominous_trial_key': 25, 'goat_horn': 12, 'bell': 25,
    'milk': .3,  # a bucket of milk used in crafting (the bucket comes back)
    # treasure-only smithing template
    'netherite_upgrade_smithing_template': 80,
}
for t in ['tube', 'brain', 'bubble', 'fire', 'horn']:
    RAW[f'{t}_coral_block'] = 1.5; RAW[f'dead_{t}_coral_block'] = .2
    RAW[f'{t}_coral'] = 1; RAW[f'dead_{t}_coral'] = .15
    RAW[f'{t}_coral_fan'] = 1; RAW[f'dead_{t}_coral_fan'] = .15
FLOWERS = ['dandelion', 'poppy', 'blue_orchid', 'allium', 'azure_bluet', 'red_tulip', 'orange_tulip', 'white_tulip',
           'pink_tulip', 'oxeye_daisy', 'cornflower', 'lily_of_the_valley', 'sunflower', 'lilac', 'rose_bush', 'peony']
for f in FLOWERS:
    RAW[f] = .12
RAW['wither_rose'] = 8
for w in WOODS:
    log = 'stem' if w in ('crimson', 'warped') else 'log'
    if w == 'bamboo':
        continue
    RAW[f'{w}_{log}'] = .3 if log == 'stem' else .25
    RAW[f'{w}_leaves'] = .05
    RAW[f'{w}_sapling'] = .2
RAW['crimson_hyphae'] = RAW['warped_hyphae'] = .3
RAW['azalea_leaves'] = .06; RAW['flowering_azalea_leaves'] = .07
for name in ['music_disc_13', 'music_disc_cat', 'music_disc_blocks', 'music_disc_chirp', 'music_disc_far',
             'music_disc_mall', 'music_disc_mellohi', 'music_disc_stal', 'music_disc_strad', 'music_disc_ward',
             'music_disc_11', 'music_disc_wait']:
    RAW[name] = 25
for name in ['music_disc_otherside', 'music_disc_relic', 'music_disc_5', 'music_disc_pigstep', 'music_disc_creator',
             'music_disc_creator_music_box', 'music_disc_precipice', 'music_disc_tears', 'music_disc_lava_chicken',
             'music_disc_bounce']:
    RAW[name] = 60

DYES = {'white': .1, 'light_gray': .12, 'gray': .12, 'black': .3, 'brown': .2, 'red': .12, 'orange': .14,
        'yellow': .12, 'lime': .2, 'green': .2, 'cyan': .25, 'light_blue': .15, 'blue': .6, 'purple': .4,
        'magenta': .2, 'pink': .14}

# Exact recipes for crafted things that don't follow a pattern: {item: ([(ingredient, count), ...], made)}
R = {
    'oak_planks': ([('oak_log', 1)], 4),
    'bricks': ([('brick', 4)], 1), 'brick': ([('clay_ball', 1), ('coal', .125)], 1),
    'nether_brick': ([('netherrack', 1), ('coal', .125)], 1), 'nether_bricks': ([('nether_brick', 4)], 1),
    'red_nether_bricks': ([('nether_brick', 2), ('nether_wart', 2)], 1),
    'clay': ([('clay_ball', 4)], 1), 'terracotta': ([('clay', 1), ('coal', .125)], 1),
    'glass': ([('sand', 1), ('coal', .125)], 1), 'glass_pane': ([('glass', 6)], 16),
    'tinted_glass': ([('glass', 1), ('amethyst_shard', 4)], 2),
    'sandstone': ([('sand', 4)], 1), 'red_sandstone': ([('red_sand', 4)], 1),
    'smooth_stone': ([('stone', 1), ('coal', .125)], 1), 'stone': ([('cobblestone', 1), ('coal', .125)], 1),
    'deepslate': ([('cobbled_deepslate', 1), ('coal', .125)], 1),
    'quartz_block': ([('quartz', 4)], 1), 'quartz_bricks': ([('quartz_block', 4)], 4),
    'quartz_pillar': ([('quartz_block', 2)], 2), 'smooth_quartz': ([('quartz_block', 1), ('coal', .125)], 1),
    'purpur_block': ([('popped_chorus_fruit', 4)], 4), 'popped_chorus_fruit': ([('chorus_fruit', 1), ('coal', .125)], 1),
    'purpur_pillar': ([('purpur_block', 2)], 2),
    'prismarine': ([('prismarine_shard', 4)], 1), 'prismarine_bricks': ([('prismarine_shard', 9)], 1),
    'dark_prismarine': ([('prismarine_shard', 8), ('ink_sac', 1)], 1),
    'sea_lantern': ([('prismarine_shard', 4), ('prismarine_crystals', 5)], 1),
    'end_stone_bricks': ([('end_stone', 4)], 4), 'packed_mud': ([('mud', 1), ('wheat', 1)], 1),
    'mud_bricks': ([('packed_mud', 4)], 4), 'polished_blackstone': ([('blackstone', 4)], 4),
    'polished_blackstone_bricks': ([('polished_blackstone', 4)], 4), 'smooth_basalt': ([('basalt', 1), ('coal', .125)], 1),
    'polished_basalt': ([('basalt', 4)], 4), 'snow_block': ([('snowball', 4)], 1), 'snow': ([('snow_block', 3)], 6),
    'packed_ice': ([('ice', 9)], 1), 'blue_ice': ([('packed_ice', 9)], 1), 'bone_meal': ([('bone', 1)], 3),
    'bone_block': ([('bone_meal', 9)], 1), 'dried_kelp_block': ([('dried_kelp', 9)], 1),
    'slime_block': ([('slime_ball', 9)], 1), 'hay_block': ([('wheat', 9)], 1), 'honey_block': ([('honey_bottle', 4)], 1),
    'honeycomb_block': ([('honeycomb', 4)], 1), 'melon': ([('melon_slice', 9)], 1),
    'resin_block': ([('resin_clump', 9)], 1), 'resin_bricks': ([('resin_brick', 4)], 1),
    'resin_brick': ([('resin_clump', 1), ('coal', .125)], 1),
    'iron_block': ([('iron_ingot', 9)], 1), 'gold_block': ([('gold_ingot', 9)], 1), 'diamond_block': ([('diamond', 9)], 1),
    'emerald_block': ([('emerald', 9)], 1), 'lapis_block': ([('lapis_lazuli', 9)], 1), 'redstone_block': ([('redstone', 9)], 1),
    'coal_block': ([('coal', 9)], 1), 'netherite_block': ([('netherite_ingot', 9)], 1), 'copper_block': ([('copper_ingot', 9)], 1),
    'raw_iron_block': ([('raw_iron', 9)], 1), 'raw_gold_block': ([('raw_gold', 9)], 1), 'raw_copper_block': ([('raw_copper', 9)], 1),
    'iron_bars': ([('iron_ingot', 6)], 16), 'iron_chain': ([('iron_ingot', 1), ('iron_nugget', 2)], 1),
    'chain': ([('iron_ingot', 1), ('iron_nugget', 2)], 1), 'iron_door': ([('iron_ingot', 6)], 3),
    'iron_trapdoor': ([('iron_ingot', 4)], 1), 'heavy_weighted_pressure_plate': ([('iron_ingot', 2)], 1),
    'light_weighted_pressure_plate': ([('gold_ingot', 2)], 1),
    'netherite_ingot': ([('netherite_scrap', 4), ('gold_ingot', 4)], 1),
    # functional
    'torch': ([('coal', 1), ('stick', 1)], 4), 'soul_torch': ([('coal', 1), ('stick', 1), ('soul_sand', 1)], 4),
    'copper_torch': ([('coal', 1), ('stick', 1), ('copper_nugget', 1)], 4),
    'lantern': ([('torch', 1), ('iron_nugget', 8)], 1), 'soul_lantern': ([('soul_torch', 1), ('iron_nugget', 8)], 1),
    'redstone_torch': ([('redstone', 1), ('stick', 1)], 1), 'end_rod': ([('blaze_rod', 1), ('popped_chorus_fruit', 1)], 4),
    'crafting_table': ([('oak_planks', 4)], 1), 'stonecutter': ([('iron_ingot', 1), ('stone', 3)], 1),
    'cartography_table': ([('paper', 2), ('oak_planks', 4)], 1), 'fletching_table': ([('flint', 2), ('oak_planks', 4)], 1),
    'smithing_table': ([('iron_ingot', 2), ('oak_planks', 4)], 1), 'grindstone': ([('stick', 2), ('stone_slab', 1), ('oak_planks', 2)], 1),
    'loom': ([('string', 2), ('oak_planks', 2)], 1), 'furnace': ([('cobblestone', 8)], 1),
    'smoker': ([('furnace', 1), ('oak_log', 4)], 1), 'blast_furnace': ([('furnace', 1), ('iron_ingot', 5), ('smooth_stone', 3)], 1),
    'campfire': ([('stick', 3), ('coal', 1), ('oak_log', 3)], 1), 'soul_campfire': ([('stick', 3), ('soul_sand', 1), ('oak_log', 3)], 1),
    'anvil': ([('iron_block', 3), ('iron_ingot', 4)], 1), 'chipped_anvil': ([('anvil', .7)], 1), 'damaged_anvil': ([('anvil', .4)], 1),
    'composter': ([('oak_slab', 7)], 1), 'note_block': ([('oak_planks', 8), ('redstone', 1)], 1),
    'jukebox': ([('oak_planks', 8), ('diamond', 1)], 1), 'enchanting_table': ([('book', 1), ('diamond', 2), ('obsidian', 4)], 1),
    'end_crystal': ([('glass', 7), ('ender_eye', 1), ('ghast_tear', 1)], 1), 'ender_eye': ([('ender_pearl', 1), ('blaze_powder', 1)], 1),
    'ender_chest': ([('obsidian', 8), ('ender_eye', 1)], 1), 'respawn_anchor': ([('crying_obsidian', 6), ('glowstone', 3)], 1),
    'lodestone': ([('chiseled_stone_bricks', 8), ('iron_ingot', 1)], 1), 'chest': ([('oak_planks', 8)], 1),
    'barrel': ([('oak_planks', 6), ('oak_slab', 2)], 1), 'trapped_chest': ([('chest', 1), ('tripwire_hook', 1)], 1),
    'shulker_box': ([('shulker_shell', 2), ('chest', 1)], 1), 'bookshelf': ([('oak_planks', 6), ('book', 3)], 1),
    'chiseled_bookshelf': ([('oak_planks', 6), ('oak_slab', 3)], 1), 'lectern': ([('oak_slab', 4), ('bookshelf', 1)], 1),
    'flower_pot': ([('brick', 3)], 1), 'decorated_pot': ([('brick', 4)], 1), 'armor_stand': ([('stick', 6), ('smooth_stone_slab', 1)], 1),
    'painting': ([('stick', 8), ('white_wool', 1)], 1), 'item_frame': ([('stick', 8), ('leather', 1)], 1),
    'glow_item_frame': ([('item_frame', 1), ('glow_ink_sac', 1)], 1), 'ladder': ([('stick', 7)], 3),
    'scaffolding': ([('bamboo', 6), ('string', 1)], 6), 'cauldron': ([('iron_ingot', 7)], 1), 'brewing_stand': ([('blaze_rod', 1), ('cobblestone', 3)], 1),
    'beehive': ([('oak_planks', 6), ('honeycomb', 3)], 1), 'candle': ([('string', 1), ('honeycomb', 1)], 1),
    'crafter': ([('iron_ingot', 5), ('crafting_table', 1), ('redstone', 2), ('dropper', 1)], 1),
    'mace': ([('heavy_core', 1), ('breeze_rod', 1)], 1),
    # redstone
    'repeater': ([('redstone_torch', 2), ('redstone', 1), ('stone', 3)], 1), 'comparator': ([('redstone_torch', 3), ('quartz', 1), ('stone', 3)], 1),
    'piston': ([('oak_planks', 3), ('cobblestone', 4), ('iron_ingot', 1), ('redstone', 1)], 1), 'sticky_piston': ([('piston', 1), ('slime_ball', 1)], 1),
    'observer': ([('cobblestone', 6), ('redstone', 2), ('quartz', 1)], 1), 'hopper': ([('iron_ingot', 5), ('chest', 1)], 1),
    'dispenser': ([('cobblestone', 7), ('bow', 1), ('redstone', 1)], 1), 'dropper': ([('cobblestone', 7), ('redstone', 1)], 1),
    'lever': ([('stick', 1), ('cobblestone', 1)], 1), 'daylight_detector': ([('glass', 3), ('quartz', 3), ('oak_slab', 3)], 1),
    'target': ([('hay_block', 1), ('redstone', 4)], 1), 'tripwire_hook': ([('iron_ingot', 1), ('stick', 1), ('oak_planks', 1)], 2),
    'redstone_lamp': ([('glowstone', 1), ('redstone', 4)], 1), 'lightning_rod': ([('copper_ingot', 3)], 1),
    'tnt': ([('gunpowder', 5), ('sand', 4)], 1), 'calibrated_sculk_sensor': ([('sculk_sensor', 1), ('amethyst_shard', 3)], 1),
    'rail': ([('iron_ingot', 6), ('stick', 1)], 16), 'powered_rail': ([('gold_ingot', 6), ('stick', 1), ('redstone', 1)], 6),
    'detector_rail': ([('iron_ingot', 6), ('stone_pressure_plate', 1), ('redstone', 1)], 6),
    'activator_rail': ([('iron_ingot', 6), ('stick', 2), ('redstone_torch', 1)], 6),
    'minecart': ([('iron_ingot', 5)], 1), 'chest_minecart': ([('minecart', 1), ('chest', 1)], 1),
    'furnace_minecart': ([('minecart', 1), ('furnace', 1)], 1), 'hopper_minecart': ([('minecart', 1), ('hopper', 1)], 1),
    'tnt_minecart': ([('minecart', 1), ('tnt', 1)], 1), 'stone_pressure_plate': ([('stone', 2)], 1),
    'polished_blackstone_pressure_plate': ([('polished_blackstone', 2)], 1),
    'stone_button': ([('stone', 1)], 1), 'polished_blackstone_button': ([('polished_blackstone', 1)], 1),
    # tools & utilities
    'flint': ([('gravel', 1.6)], 1), 'flint_and_steel': ([('iron_ingot', 1), ('flint', 1)], 1),
    'fishing_rod': ([('stick', 3), ('string', 2)], 1), 'carrot_on_a_stick': ([('fishing_rod', 1), ('carrot', 1)], 1),
    'warped_fungus_on_a_stick': ([('fishing_rod', 1), ('warped_fungus', 1)], 1), 'shears': ([('iron_ingot', 2)], 1),
    'brush': ([('feather', 1), ('copper_ingot', 1), ('stick', 1)], 1), 'compass': ([('iron_ingot', 4), ('redstone', 1)], 1),
    'recovery_compass': ([('echo_shard', 8), ('compass', 1)], 1), 'clock': ([('gold_ingot', 4), ('redstone', 1)], 1),
    'spyglass': ([('copper_ingot', 2), ('amethyst_shard', 1)], 1), 'map': ([('paper', 8), ('compass', 1)], 1),
    'lead': ([('string', 4), ('slime_ball', 1)], 2), 'bucket': ([('iron_ingot', 3)], 1), 'water_bucket': ([('bucket', 1)], 1),
    'lava_bucket': ([('bucket', 1), ('coal', 2)], 1), 'milk_bucket': ([('bucket', 1)], 1),
    'powder_snow_bucket': ([('bucket', 1), ('snowball', 4)], 1),
    'cod_bucket': ([('bucket', 1), ('cod', 1)], 1), 'salmon_bucket': ([('bucket', 1), ('salmon', 1)], 1),
    'tropical_fish_bucket': ([('bucket', 1), ('tropical_fish', 1)], 1), 'pufferfish_bucket': ([('bucket', 1), ('pufferfish', 1)], 1),
    'axolotl_bucket': ([('bucket', 1), ('slime_ball', 2)], 1), 'tadpole_bucket': ([('bucket', 1), ('slime_ball', 1)], 1),
    'book': ([('paper', 3), ('leather', 1)], 1), 'paper': ([('sugar_cane', 3)], 3), 'writable_book': ([('book', 1), ('ink_sac', 1), ('feather', 1)], 1),
    'bundle': ([('string', 1), ('leather', 1)], 1), 'wind_charge': ([('breeze_rod', 1)], 4),
    'firework_rocket': ([('paper', 1), ('gunpowder', 1)], 3), 'firework_star': ([('gunpowder', 1), ('white_dye', 1)], 1),
    'glass_bottle': ([('glass', 3)], 3), 'bowl': ([('oak_planks', 3)], 4), 'leather_horse_armor': ([('leather', 7)], 1),
    'nether_brick_fence': ([('nether_bricks', 4), ('nether_brick', 2)], 6),
    # combat
    'bow': ([('stick', 3), ('string', 3)], 1), 'crossbow': ([('stick', 3), ('string', 2), ('iron_ingot', 1), ('tripwire_hook', 1)], 1),
    'arrow': ([('flint', 1), ('stick', 1), ('feather', 1)], 4), 'spectral_arrow': ([('arrow', 4), ('glowstone_dust', 4)], 2),
    'shield': ([('oak_planks', 6), ('iron_ingot', 1)], 1), 'turtle_helmet': ([('turtle_scute', 5)], 1),
    'wolf_armor': ([('armadillo_scute', 6)], 1),
    # food
    'bread': ([('wheat', 3)], 1), 'cookie': ([('wheat', 2), ('cocoa_beans', 1)], 8), 'cake': ([('milk', 3), ('sugar', 2), ('egg', 1), ('wheat', 3)], 1),
    'pumpkin_pie': ([('pumpkin', 1), ('sugar', 1), ('egg', 1)], 1), 'golden_carrot': ([('carrot', 1), ('gold_nugget', 8)], 1),
    'golden_apple': ([('apple', 1), ('gold_ingot', 8)], 1), 'glistering_melon_slice': ([('melon_slice', 1), ('gold_nugget', 8)], 1),
    'mushroom_stew': ([('bowl', 1), ('brown_mushroom', 1), ('red_mushroom', 1)], 1), 'beetroot_soup': ([('bowl', 1), ('beetroot', 6)], 1),
    'rabbit_stew': ([('bowl', 1), ('rabbit', 1), ('carrot', 1), ('baked_potato', 1), ('brown_mushroom', 1)], 1),
    'suspicious_stew': ([('mushroom_stew', 1), ('dandelion', 1)], 1),
    'baked_potato': ([('potato', 1), ('coal', .125)], 1), 'dried_kelp': ([('kelp', 1), ('coal', .125)], 1),
    'fermented_spider_eye': ([('spider_eye', 1), ('sugar', 1), ('brown_mushroom', 1)], 1),
    'blaze_powder': ([('blaze_rod', 1)], 2), 'magma_cream': ([('blaze_powder', 1), ('slime_ball', 1)], 1),
    'fire_charge': ([('blaze_powder', 1), ('coal', 1), ('gunpowder', 1)], 3),
    'mojang_banner_pattern': ([('paper', 1), ('enchanted_golden_apple', 1)], 1),
    'map': ([('paper', 8), ('compass', 1)], 1), 'nether_wart_block': ([('nether_wart', 9)], 1),
    'jack_o_lantern': ([('carved_pumpkin', 1), ('torch', 1)], 1), 'glow_item_frame': ([('item_frame', 1), ('glow_ink_sac', 1)], 1),
    'sugar': ([('sugar_cane', 1)], 1), 'amethyst_block': ([('amethyst_shard', 4)], 1), 'glowstone': ([('glowstone_dust', 4)], 1),
    'mossy_cobblestone': ([('cobblestone', 1), ('vine', 1)], 1), 'mossy_stone_bricks': ([('stone_bricks', 1), ('vine', 1)], 1),
    'moss_carpet': ([('moss_block', 2)], 3), 'pale_moss_carpet': ([('pale_moss_block', 2)], 3),
    'coarse_dirt': ([('dirt', 2), ('gravel', 2)], 4), 'leather_horse_armor': ([('leather', 7)], 1),
    'beacon': ([('nether_star', 1), ('obsidian', 3), ('glass', 5)], 1), 'conduit': ([('heart_of_the_sea', 1), ('nautilus_shell', 8)], 1),
    'lodestone': ([('chiseled_stone_bricks', 8), ('iron_ingot', 1)], 1), 'cracked_stone_bricks': ([('stone_bricks', 1), ('coal', .125)], 1),
    'sulfur_cube_bucket': ([('bucket', 1), ('slime_ball', 2)], 1), 'copper_horse_armor': ([('copper_ingot', 7)], 1),
    'netherite_horse_armor': ([('diamond_horse_armor', 1), ('netherite_ingot', 1)], 1),
}
RAW['golden_dandelion'] = 3

# Not sold: no survival way to get them, or they only make sense with extra data the shop doesn't set.
EXCLUDE = {
    'bedrock', 'reinforced_deepslate', 'end_portal_frame', 'budding_amethyst', 'spawner', 'trial_spawner', 'vault',
    'player_head', 'dragon_egg', 'frogspawn', 'suspicious_sand', 'suspicious_gravel', 'petrified_oak_slab',
    'command_block', 'repeating_command_block', 'chain_command_block', 'command_block_minecart', 'barrier',
    'structure_block', 'structure_void', 'jigsaw', 'light', 'debug_stick', 'knowledge_book', 'test_block',
    'test_instance_block', 'written_book', 'filled_map', 'enchanted_book', 'potion', 'splash_potion',
    'lingering_potion', 'tipped_arrow', 'ominous_bottle', 'farmland', 'frosted_ice', 'infested_stone',
    'infested_cobblestone', 'infested_stone_bricks', 'infested_mossy_stone_bricks', 'infested_cracked_stone_bricks',
    'infested_chiseled_stone_bricks', 'infested_deepslate', 'firework_star',
}

DROP_TABS = {'Spawn Eggs', 'Operator'}
TABS = [(0, 'Building Blocks'), (462, 'Colored Blocks'), (660, 'Natural Blocks'), (879, 'Functional Blocks'),
        (1017, 'Redstone'), (1046, 'Tools & Utilities'), (1181, 'Combat'), (1249, 'Food & Drinks'),
        (1293, 'Ingredients'), (1429, 'Spawn Eggs'), (1518, 'Operator')]
TAB_FIRST = {'Building Blocks': 'oak_log', 'Colored Blocks': 'white_wool', 'Natural Blocks': 'grass_block',
             'Functional Blocks': 'torch', 'Redstone': 'redstone', 'Tools & Utilities': 'wooden_shovel',
             'Combat': 'wooden_sword', 'Food & Drinks': 'apple', 'Ingredients': 'coal', 'Spawn Eggs': 'spawner',
             'Operator': 'petrified_oak_slab'}

UNSTACKABLE = re.compile(r'(_sword|_pickaxe|_axe|_shovel|_hoe|_helmet|_chestplate|_leggings|_boots|_bucket|_boat|_raft'
                         r'|_minecart|minecart|_horse_armor|_stew|_soup|shulker_box|^bed$|_bed$|^bow$|crossbow|trident'
                         r'|shield|elytra|fishing_rod|_on_a_stick|flint_and_steel|shears|brush|spyglass|saddle'
                         r'|totem_of_undying|music_disc|writable_book|cake|mace|goat_horn|wolf_armor|bundle|turtle_helmet'
                         r'|^potion$|_banner_pattern|recovery_compass$)')
STACK16 = re.compile(r'(ender_pearl|snowball|^egg$|_egg$|^sign$|_sign$|_hanging_sign$|_banner$|honey_bottle|bucket$|armor_stand'
                     r'|wind_charge|written_book)')


def stack_size(item):
    if item in ('bucket',):
        return 16
    if UNSTACKABLE.search(item):
        return 1
    if STACK16.search(item):
        return 16
    return 64


prices = {}
missing = set()


def price(item, depth=0):
    """Credits per item, or None if it isn't sold."""
    if item in prices:
        return prices[item]
    if depth > 30 or item in EXCLUDE:
        return None
    value = rule(item, depth)
    prices[item] = value
    return value


def p(item, depth):
    v = price(item, depth + 1)
    if v is None:
        missing.add(item)
        return math.inf
    return v


def recipe(parts, made, depth):
    return sum(p(i, depth) * n for i, n in parts) / made * CRAFT


def first_existing(candidates):
    for c in candidates:
        if c in ITEMS or c in RAW or c in R:
            return c
    return None


def base_of(stem):
    """The full block a stairs/slab/wall/etc. is made from."""
    alt = stem.replace('brick', 'bricks').replace('tile', 'tiles')
    return first_existing([stem, stem + 's', alt, stem + '_block', stem + '_planks', alt.replace('bricks_', 'brick_')])


def rule(item, depth):
    if item in R:
        return None if R[item] is None else recipe(*R[item], depth)
    if item in RAW:
        return RAW[item]
    # waxed copper: the copper plus a honeycomb's worth; oxidation is free
    m = re.match(r'waxed_(.+)', item)
    if m:
        base = p(m.group(1), depth)
        if m.group(1) == 'copper' or m.group(1).endswith('_copper'):
            base = p('copper_block' if m.group(1) == 'copper' else m.group(1), depth)
        return base + RAW['honeycomb'] * (1 if 'block' in item or m.group(1).endswith('copper') else .25) * .5
    m = re.match(r'(exposed|weathered|oxidized)_(.+)', item)
    if m:
        return p('copper_block' if m.group(2) == 'copper' else m.group(2), depth)
    if item.startswith('copper_') or item.startswith('cut_copper') or item.startswith('chiseled_copper'):
        cb = p('copper_block', depth)
        ci = p('copper_ingot', depth)
        table = {'copper_grate': cb / 4, 'cut_copper': cb / 4, 'chiseled_copper': cb / 4, 'copper_door': ci * 2, 'copper_trapdoor': ci * 4,
                 'copper_bulb': (cb * 3 + p('blaze_rod', depth) + p('redstone', depth)) / 4, 'copper_bars': ci * 6 / 16,
                 'copper_chain': ci + RAW['copper_nugget'] * 2, 'copper_lantern': p('copper_torch', depth) + RAW['copper_nugget'] * 8,
                 'copper_chest': ci * 8, 'copper_golem_statue': cb * 2}
        stem = item
        for suffix, factor in (('_stairs', 1), ('_slab', .5)):
            if item.endswith(suffix):
                stem = item[:-len(suffix)]
                return table.get(stem, cb) * factor * CRAFT
        if item in table:
            return table[item] * CRAFT
    # colours
    for c in COLORS:
        if item.startswith(c + '_'):
            rest = item[len(c) + 1:]
            dye = DYES[c]
            table = {'dye': dye, 'wool': p('wool', depth), 'carpet': p('wool', depth) * 2 / 3,
                     'terracotta': p('terracotta', depth) + dye / 8, 'glazed_terracotta': p('terracotta', depth) + dye / 8 + .06,
                     'concrete_powder': (p('sand', depth) * 4 + p('gravel', depth) * 4 + dye) / 8, 'concrete': (p('sand', depth) * 4 + p('gravel', depth) * 4 + dye) / 8 + .02,
                     'stained_glass': p('glass', depth) + dye / 8, 'stained_glass_pane': (p('glass', depth) + dye / 8) * 6 / 16,
                     'bed': p('wool', depth) * 3 + p('oak_planks', depth) * 3, 'banner': p('wool', depth) * 6 + p('stick', depth),
                     'candle': p('candle', depth) + dye, 'shulker_box': p('shulker_box', depth) + dye,
                     'bundle': p('string', depth) + p('leather', depth) + dye, 'harness': p('leather', depth) * 3 + p('glass', depth) * 2 + dye}
            if rest in table:
                return table[rest] * (CRAFT if rest not in ('dye',) else 1)
    if item.endswith('_dye') and item[:-4] in DYES:
        return DYES[item[:-4]]
    # wood families
    for w in WOODS:
        if item.startswith(w + '_') or item.startswith('stripped_' + w + '_'):
            rest = item[len(w) + 1:] if item.startswith(w + '_') else item[len('stripped_' + w) + 1:]
            log = .3 if w in ('crimson', 'warped') else .25
            plank = (RAW['bamboo'] * 2 if w == 'bamboo' else log / 4) * CRAFT
            table = {'log': log, 'stem': log, 'wood': log * 4 / 3, 'hyphae': log * 4 / 3, 'planks': plank,
                     'stairs': plank * 1.5, 'slab': plank / 2, 'fence': (plank * 4 + .04) / 3, 'fence_gate': plank * 2 + .08,
                     'door': plank * 2, 'trapdoor': plank * 3, 'pressure_plate': plank * 2, 'button': plank,
                     'sign': (plank * 6 + .02) / 3, 'hanging_sign': (log * 6 + p('iron_chain', depth) * 2) / 6,
                     'boat': plank * 5, 'chest_boat': plank * 5 + p('chest', depth), 'raft': plank * 5,
                     'chest_raft': plank * 5 + p('chest', depth), 'shelf': log * 6 / 6, 'block': RAW['bamboo'] * 9,
                     'mosaic': plank, 'mosaic_stairs': plank * 1.5, 'mosaic_slab': plank / 2}
            if rest in table:
                return table[rest] * (1 if rest in ('log', 'stem') else CRAFT)
    # stone-like derived blocks
    for suffix, factor in (('_stairs', 1), ('_slab', .5), ('_wall', 1), ('_fence', 1)):
        if item.endswith(suffix):
            base = base_of(item[:-len(suffix)])
            if base:
                return p(base, depth) * factor * CRAFT
    for prefix in ('polished_', 'chiseled_', 'cut_', 'smooth_', 'cracked_', 'mossy_'):
        if item.startswith(prefix):
            base = first_existing([item[len(prefix):], item[len(prefix):] + '_block'])
            if base:
                return p(base, depth) * CRAFT
    for suffix in ('_bricks', '_tiles', '_brick', '_tile'):
        if item.endswith(suffix):
            base = first_existing([item[:-len(suffix)], 'polished_' + item[:-len(suffix)], item[:-len(suffix)] + '_block'])
            if base:
                return p(base, depth) * CRAFT
    m = re.match(r'(wooden|stone|iron|golden|diamond|netherite|copper)_(sword|pickaxe|axe|shovel|hoe|spear)', item)
    if m:
        mat = {'wooden': ('oak_planks', 'oak_planks'), 'stone': ('cobblestone', 'cobblestone'), 'iron': ('iron_ingot', 'iron_ingot'),
               'golden': ('gold_ingot', 'gold_ingot'), 'diamond': ('diamond', 'diamond'), 'copper': ('copper_ingot', 'copper_ingot'),
               'netherite': (None, None)}[m.group(1)][0]
        heads = {'sword': (2, 1), 'pickaxe': (3, 2), 'axe': (3, 2), 'shovel': (1, 2), 'hoe': (2, 2), 'spear': (1, 2)}[m.group(2)]
        if m.group(1) == 'netherite':
            return p(f'diamond_{m.group(2)}', depth) + p('netherite_ingot', depth) + p('netherite_upgrade_smithing_template', depth)
        return (p(mat, depth) * heads[0] + p('stick', depth) * heads[1]) * CRAFT
    m = re.match(r'(leather|chainmail|iron|golden|diamond|netherite|copper)_(helmet|chestplate|leggings|boots)', item)
    if m:
        pieces = {'helmet': 5, 'chestplate': 8, 'leggings': 7, 'boots': 4}[m.group(2)]
        if m.group(1) == 'netherite':
            return p(f'diamond_{m.group(2)}', depth) + p('netherite_ingot', depth) + p('netherite_upgrade_smithing_template', depth)
        mat = {'leather': 'leather', 'chainmail': 'iron_ingot', 'iron': 'iron_ingot', 'golden': 'gold_ingot',
               'diamond': 'diamond', 'copper': 'copper_ingot'}[m.group(1)]
        return p(mat, depth) * pieces * (1.5 if m.group(1) == 'chainmail' else 1) * CRAFT
    m = re.match(r'(iron|golden|diamond|netherite|copper)_(nautilus_armor)', item)
    if m:
        return p({'iron': 'iron_horse_armor', 'golden': 'golden_horse_armor', 'diamond': 'diamond_horse_armor',
                  'copper': 'iron_horse_armor', 'netherite': 'diamond_horse_armor'}[m.group(1)], depth)
    if item.endswith('_armor_trim_smithing_template'):
        return 30  # found once, then copied with diamonds
    if item.endswith('_pottery_sherd'):
        return 10
    if item.endswith('_banner_pattern'):
        return p('paper', depth) + 2
    if item in ('skull_banner_pattern', 'creeper_banner_pattern', 'flower_banner_pattern'):
        return 8
    if item.startswith('cooked_'):
        return p(item[7:], depth) + RAW['coal'] / 8
    if item.endswith('_froglight'):
        return 4
    if item.startswith('dead_') and item.endswith('_coral_wall_fan'):
        return .15
    missing.add(item)
    return None


if __name__ == '__main__':
    manifest = json.load(open(sys.argv[1]))['items']
    ITEMS = [k.split(':', 1)[1] for k in manifest]
    tab_start = sorted((ITEMS.index(first), tab) for tab, first in TAB_FIRST.items())
    out = []
    for index, item in enumerate(ITEMS):
        tab = [t for start, t in tab_start if start <= index][-1]
        if tab in DROP_TABS or item in EXCLUDE:
            continue
        value = price(item)
        if value is None or math.isinf(value):
            continue
        cents = max(1, round(value * 100))
        out.append({'id': item, 'tab': tab, 'stack': stack_size(item), 'price': cents})
    unpriced = [i for i in ITEMS if i not in EXCLUDE and (price(i) is None or math.isinf(price(i)))
                and [t for s, t in tab_start if s <= ITEMS.index(i)][-1] not in DROP_TABS]
    if unpriced:
        print(f'{len(unpriced)} items have no price and are left out:', ' '.join(unpriced), file=sys.stderr)
    target = sys.argv[2] if len(sys.argv) > 2 else 'src/main/resources/shop-items.json'
    with open(target, 'w') as f:
        json.dump({'items': out}, f, separators=(',', ':'))
    print(f'{len(out)} items written to {target}', file=sys.stderr)

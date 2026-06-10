package com.pixelknockoff.interceptor;

import com.pixelknockoff.PixelKnockOffPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 拍落技能拦截器 (纯反射版 - 无 Forge 事件)
 *
 * 核心策略：
 * 1. 不再使用 Forge 事件总线（Bukkit 插件注册 Forge 事件会因类加载器隔离崩溃）
 * 2. 改用 Bukkit 定时任务，每秒扫描一次活跃战斗
 * 3. 检测到宝可梦携带黑名单道具后，缓存该道具引用
 * 4. 如果检测到道具被移除（removeHeldItem 调用后将字段置 null），立即通过反射恢复
 *
 * 这样就实现了「黑名单中的携带物无法被拍落」的效果。
 */
public class KnockOffInterceptor {

    private final PixelKnockOffPlugin plugin;

    // Pixelmon 关键类路径
    private static final String PIXELMON_WRAPPER_CLASS = "com.pixelmonmod.pixelmon.battles.controller.participants.PixelmonWrapper";
    private static final String BATTLE_REGISTRY_CLASS = "com.pixelmonmod.pixelmon.battles.BattleRegistry";

    // BattleController 可能存在的多个类名（不同版本名称不同）
    private static final String[] BATTLE_CONTROLLER_CLASSES = {
        "com.pixelmonmod.pixelmon.battles.controller.BattleControllerBase",
        "com.pixelmonmod.pixelmon.battles.controller.BattleController",
        "com.pixelmonmod.pixelmon.battles.controller.BattleControllerAbstract",
        "com.pixelmonmod.pixelmon.battles.controller.BattleManager"
    };

    // ========== 反射缓存 ==========
    private Method hasHeldItemMethod;
    private Method getHeldItemMethod;
    private Method getNicknameMethod;
    private Method getParticipantsMethod;
    private Method getBattlesMethod;

    // 静态字段备用
    private Field battlesField;
    private Field participantsField;

    // PixelmonWrapper 上的携带物字段（关键：removeHeldItem 会将其置 null）
    private Field heldItemField;

    // Pokemon 对象（实际持有携带物的内部对象）
    private Field pokemonField;
    private Method setPokemonHeldItemMethod;
    private Field pokemonHeldItemField;
    private static final String POKEMON_CLASS = "com.pixelmonmod.pixelmon.api.pokemon.Pokemon";

    // 形态相关（恢复携带物后需触发形态重算）
    private Field entityField;
    private Method updateFormMethod;
    private static final String ENTITY_CLASS = "com.pixelmonmod.pixelmon.entities.pixelmon.PixelmonEntity";

    // 形态相关 — 用形态名称字符串来强制保持（避免可变对象引用问题）
    private Field pokemonFormField;      // Pokemon.form (Stats 对象)
    private Field statsNameField;        // Stats.name (String)
    private Method pokemonGetFormMethod; // Pokemon.getForm() 备用
    private Method pokemonSetFormMethod; // Pokemon.setForm() 方法
    private Field pokemonSpeciesField;   // Pokemon.species (Species 对象)
    private Method speciesGetFormsMethod; // Species.getForms() 方法

    // ========== 运行时状态 ==========
    // 缓存的宝可梦数据: 唯一标识 -> {itemStack, formName}
    private static class PokemonCache {
        final Object itemStack;
        final String formName;      // 形态名称字符串（如 "origin"），不可变，安全
        PokemonCache(Object itemStack, String formName) {
            this.itemStack = itemStack;
            this.formName = formName;
        }
    }
    private final Map<String, PokemonCache> protectedItems = new HashMap<>();

    private BukkitTask scanTask;
    private boolean running = false;

    public KnockOffInterceptor(PixelKnockOffPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 启动拦截器
     */
    public void start() {
        if (running) return;
        running = true;

        warmupReflection();

        // 检查核心组件是否就绪
        if (hasHeldItemMethod == null || getHeldItemMethod == null) {
            plugin.getLogger().severe("PixelmonWrapper 反射预热失败，关键方法未找到！");
            return;
        }
        if (getBattlesMethod == null && battlesField == null) {
            plugin.getLogger().warning("BattleRegistry 反射预热失败，无法获取活跃战斗列表！");
            return;
        }

        startScanTask();
        plugin.getLogger().info("拍落拦截器已启动 (定时扫描模式)");
    }

    /**
     * 停止拦截器
     */
    public void stop() {
        running = false;
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        protectedItems.clear();
    }

    /**
     * 预热反射 — 每个类独立加载，不因一个类找不到就整体崩溃
     */
    private void warmupReflection() {
        // 1. PixelmonWrapper (核心类，必须成功)
        try {
            Class<?> wrapperClass = Class.forName(PIXELMON_WRAPPER_CLASS);
            this.hasHeldItemMethod = findMethod(wrapperClass, "hasHeldItem");
            this.getHeldItemMethod = findMethod(wrapperClass, "getHeldItem");
            this.getNicknameMethod = findMethod(wrapperClass, "getNickname");

            // PixelmonWrapper 上无 heldItem 字段，实际存在 pokemon 对象中
            this.pokemonField = findField(wrapperClass, "pokemon");

            plugin.getLogger().info("[预热] PixelmonWrapper 加载成功");
        } catch (Exception e) {
            plugin.getLogger().warning("[预热] PixelmonWrapper 加载失败: " + e.getMessage());
        }

        // 1.5 Pokemon 对象（实际持有携带物）
        try {
            Class<?> pokemonClass = Class.forName(POKEMON_CLASS);
            // 查找 setHeldItem 方法
            this.setPokemonHeldItemMethod = findMethod(pokemonClass, "setHeldItem", Object.class);
            // 查找 heldItem 字段（备用）
            for (String name : new String[]{"heldItem", "heldItemRef", "item", "heldItemStack"}) {
                Field f = findField(pokemonClass, name);
                if (f != null) {
                    this.pokemonHeldItemField = f;
                    break;
                }
            }
            // 查找 form 字段（形态恢复用）
            for (String name : new String[]{"form", "pokemonForm", "displayForm", "stats"}) {
                Field f = findField(pokemonClass, name);
                if (f != null) {
                    this.pokemonFormField = f;
                    break;
                }
            }
            // 查找 Pokemon.getForm() 方法（备用）
            this.pokemonGetFormMethod = findMethod(pokemonClass, "getForm");
            // 查找 Pokemon.setForm() / setFormIndex() 等方法
            for (String n : new String[]{"setForm", "setFormIndex", "setFormByName", "updateForm"}) {
                // 尝试无参、String参、int参
                Method m = findMethod(pokemonClass, n, String.class);
                if (m == null) m = findMethod(pokemonClass, n, int.class);
                if (m == null) m = findMethod(pokemonClass, n);
                if (m != null) {
                    this.pokemonSetFormMethod = m;
                    break;
                }
            }
            // 查找 Pokemon.species 字段
            this.pokemonSpeciesField = findField(pokemonClass, "species");
            // 如果找到了 pokemonFormField，在 Stats 类上找 name 字段
            if (pokemonFormField != null) {
                Class<?> statsClass = pokemonFormField.getType();
                this.statsNameField = findField(statsClass, "name");
                // 也尝试在父类中找
                if (statsNameField == null) {
                    Class<?> superClass = statsClass.getSuperclass();
                    while (superClass != null && superClass != Object.class) {
                        this.statsNameField = findField(superClass, "name");
                        if (statsNameField != null) break;
                        superClass = superClass.getSuperclass();
                    }
                }
            }
            // 查找 Species.getForms() 方法（用于找到所有可用形态）
            if (pokemonSpeciesField != null) {
                try {
                    Class<?> speciesClass = pokemonSpeciesField.getType();
                    this.speciesGetFormsMethod = findMethod(speciesClass, "getForms");
                    if (speciesGetFormsMethod == null) {
                        speciesGetFormsMethod = findMethod(speciesClass, "getAllForms");
                    }
                } catch (Exception ignored) {}
            }
            plugin.getLogger().info("[预热] Pokemon 加载成功, setHeldItem=" + (setPokemonHeldItemMethod != null) + " heldItemField=" + (pokemonHeldItemField != null) + " formField=" + (pokemonFormField != null) + " statsNameField=" + (statsNameField != null) + " setFormMethod=" + (pokemonSetFormMethod != null));
        } catch (Exception e) {
            plugin.getLogger().warning("[预热] Pokemon 加载失败: " + e.getMessage());
        }

        // 1.6 PixelmonEntity（用于恢复后的形态触发）
        try {
            // 先从 PixelmonWrapper 获取 entity 字段
            Class<?> wrapperClass = Class.forName(PIXELMON_WRAPPER_CLASS);
            this.entityField = findField(wrapperClass, "entity");

            // 在 PixelmonEntity 上找 updateForm 相关方法
            Class<?> entityClass = Class.forName(ENTITY_CLASS);
            for (String name : new String[]{"updateForm", "recalculateForm", "checkForm", "refreshForm", "updatePokemonForm"}) {
                Method m = findMethod(entityClass, name);
                if (m != null) {
                    this.updateFormMethod = m;
                    break;
                }
            }
            plugin.getLogger().info("[预热] Entity 加载成功, entityField=" + (entityField != null) + " updateFormMethod=" + (updateFormMethod != null));
        } catch (Exception e) {
            plugin.getLogger().warning("[预热] Entity 加载失败: " + e.getMessage());
        }

        // 2. BattleRegistry (获取活跃战斗)
        try {
            Class<?> registryClass = Class.forName(BATTLE_REGISTRY_CLASS);
            // 尝试静态方法
            for (String name : new String[]{"getBattles", "getActiveBattles", "getBattleList", "getBattleMap", "getAllBattles", "getCurrentBattles"}) {
                this.getBattlesMethod = findMethod(registryClass, name);
                if (this.getBattlesMethod != null) break;
            }
            // 尝试 getInstance + getBattles 模式
            if (this.getBattlesMethod == null) {
                try {
                    Method getInstance = findMethod(registryClass, "getInstance");
                    if (getInstance != null) {
                        Object instance = getInstance.invoke(null);
                        if (instance != null) {
                            for (String name : new String[]{"getBattles", "getActiveBattles", "getBattleList", "getAllBattles", "getCurrentBattles"}) {
                                this.getBattlesMethod = findMethod(instance.getClass(), name);
                                if (this.getBattlesMethod != null) break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            // 备用: 静态字段
            if (this.getBattlesMethod == null) {
                for (String name : new String[]{"battles", "activeBattles", "battleMap", "battleList", "currentBattles", "battlesMap", "BATTLES_BY_ID", "BATTLES", "battlesById"}) {
                    Field f = findField(registryClass, name);
                    if (f != null) {
                        this.battlesField = f;
                        break;
                    }
                }
            }
            plugin.getLogger().info("[预热] BattleRegistry 加载成功, getBattles=" + (getBattlesMethod != null) + " battlesField=" + (battlesField != null));
        } catch (Exception e) {
            plugin.getLogger().warning("[预热] BattleRegistry 加载失败: " + e.getMessage());
        }

        // 3. BattleController (获取参与者，尝试多个类名)
        for (String className : BATTLE_CONTROLLER_CLASSES) {
            try {
                Class<?> controllerClass = Class.forName(className);
                this.getParticipantsMethod = findMethod(controllerClass, "getParticipants");
                if (this.getParticipantsMethod == null) {
                    this.getParticipantsMethod = findMethodAny(controllerClass, "getParticipants");
                }
                if (this.getParticipantsMethod == null) {
                    for (String name : new String[]{"participants", "participantsList", "allParticipants"}) {
                        Field f = findField(controllerClass, name);
                        if (f != null) {
                            this.participantsField = f;
                            break;
                        }
                    }
                }
                plugin.getLogger().info("[预热] BattleController 加载成功: " + className
                    + " getParticipants=" + (getParticipantsMethod != null)
                    + " participantsField=" + (participantsField != null));
                break; // 找到一个能用的就行
            } catch (Exception ignored) {
                // 继续尝试下一个类名
            }
        }

        // 4. 如果 BattleController 的方式都没找到，尝试直接从 PixelmonWrapper 列表中获得参与者
        if (getParticipantsMethod == null && participantsField == null) {
            plugin.getLogger().info("[预热] 未找到 BattleController，将尝试从参与者中提取 Wrapper");
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[预热] hasHeldItem=" + (hasHeldItemMethod != null)
                    + " heldItemField=" + (heldItemField != null)
                    + " getBattles=" + (getBattlesMethod != null) + "/" + (battlesField != null)
                    + " getParticipants=" + (getParticipantsMethod != null) + "/" + (participantsField != null));
        }
    }

    /**
     * 启动定时扫描任务 (每 10 tick = 0.5 秒)
     */
    private void startScanTask() {
        scanTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) {
                    cancel();
                    return;
                }
                try {
                    scanAndProtect();
                } catch (Exception e) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().warning("[扫描] 异常: " + e.getMessage());
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 10L);
    }

    /**
     * 扫描所有活跃战斗，保护黑名单携带物
     */
    private void scanAndProtect() {
        List<Object> battles = getActiveBattles();
        if (battles == null || battles.isEmpty()) {
            if (!protectedItems.isEmpty()) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[扫描] 战斗结束，清理 " + protectedItems.size() + " 个保护");
                }
                protectedItems.clear();
            }
            return;
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[扫描] 发现 " + battles.size() + " 场活跃战斗");
        }

        // 记录当前战斗中所有宝可梦唯一标识
        Set<String> activeKeys = new HashSet<>();

        for (Object controller : battles) {
            if (controller == null) continue;

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[扫描] 战斗控制器=" + controller.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(controller)));
            }

            List<Object> participants = getParticipants(controller);
            if (participants == null) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[扫描]   participants=null");
                }
                continue;
            }

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[扫描]   participants大小=" + participants.size());
            }

            for (Object participant : participants) {
                if (participant == null) continue;

                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[扫描]   参与者类型=" + participant.getClass().getSimpleName() + " class=" + participant.getClass().getName());
                }

                Object wrapper = extractWrapper(participant);
                if (wrapper == null) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[扫描]   无法提取wrapper");
                    }
                    continue;
                }

                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[扫描]   wrapper类型=" + wrapper.getClass().getSimpleName());
                }

                String key = getPokemonKey(wrapper);
                if (key == null) continue;
                activeKeys.add(key);

                // 检查是否有黑名单道具
                checkAndProtect(wrapper, key);
            }
        }

        // 清理已不在战斗中的缓存
        protectedItems.keySet().removeIf(k -> !activeKeys.contains(k));
    }

    /**
     * 检查并保护单个宝可梦的携带物
     *
     * 重要：PixelmonWrapper.getHeldItem() 返回的是 HeldItem 类型（如 OriginFormItem），
     * 用于黑名单判断。但 Pokemon.heldItem 字段是 ItemStack 类型（Minecraft 物品栈），
     * 两者类型不同，不能混用。
     *
     * 所以：用 getHeldItem() 检查黑名单，但缓存 pokemon.heldItem (ItemStack) 用于恢复。
     */
    private void checkAndProtect(Object wrapper, String key) {
        try {
            // 获取 pokemon 对象
            Object pokemon = getFieldValue(wrapper, "pokemon");
            if (pokemon == null) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[checkAndProtect] 无法获取 pokemon 对象");
                }
                return;
            }

            // 检查当前是否有携带物 (通过 hasHeldItem 方法)
            Object hasItem = invoke(hasHeldItemMethod, wrapper);
            boolean hasItemNow = (hasItem != null && (Boolean) hasItem);

            if (plugin.getConfigManager().isDebug()) {
                Object nickname = invoke(getNicknameMethod, wrapper);
                plugin.getLogger().info("[checkAndProtect] " + nickname + " hasItem=" + hasItemNow + " key=" + key + " cached=" + protectedItems.containsKey(key));
            }

            if (hasItemNow) {
                // 用 getHeldItem() 获得 HeldItem 来判断黑名单
                Object heldItem = invoke(getHeldItemMethod, wrapper);
                if (heldItem == null) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[checkAndProtect] getHeldItem 返回 null");
                    }
                    return;
                }

                String itemId = getItemIdentifier(heldItem);
                if (itemId == null) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[checkAndProtect] 无法获取物品ID, heldItem类=" + heldItem.getClass().getName());
                    }
                    return;
                }

                boolean isBlocked = plugin.getConfigManager().isBlocked(itemId);
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[checkAndProtect] itemId=" + itemId + " isBlocked=" + isBlocked);
                }

                if (isBlocked) {
                    // 缓存 ItemStack (从 pokemon.heldItem 字段读取)
                    Object itemStack = null;
                    if (pokemonHeldItemField != null) {
                        itemStack = pokemonHeldItemField.get(pokemon);
                    }
                    if (itemStack == null) {
                        itemStack = heldItem;
                    }

                    PokemonCache existing = protectedItems.get(key);
                    String currentFormName = (existing != null) ? existing.formName : null;

                    // 只有第一次缓存形态名称，后续永不覆盖
                    if (currentFormName == null) {
                        currentFormName = getFormName(pokemon);
                    }

                    if (existing == null) {
                        protectedItems.put(key, new PokemonCache(itemStack, currentFormName));
                        plugin.getLogger().info("[保护] " + itemId + " 已加入保护列表 (key=" + key + ") formName=" + currentFormName);
                    } else {
                        // 只更新道具引用，形态保持第一次缓存的值
                        protectedItems.put(key, new PokemonCache(itemStack, currentFormName));
                        if (plugin.getConfigManager().isDebug()) {
                            plugin.getLogger().info("[保护] " + itemId + " 更新缓存引用");
                        }
                    }
                } else if (protectedItems.containsKey(key)) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[checkAndProtect] 不再在黑名单中，移除保护");
                    }
                    protectedItems.remove(key);
                }
            } else {
                // 没有携带物 — 可能是被 KnockOff 移除了
                PokemonCache cached = protectedItems.get(key);
                if (cached != null) {
                    plugin.getLogger().info("[保护] 检测到携带物被移除，尝试恢复...");
                    if (restoreHeldItem(pokemon, cached)) {
                        plugin.getLogger().info("[恢复] 携带物已成功恢复 (key=" + key + ")");
                    } else {
                        plugin.getLogger().warning("[恢复] 恢复失败！");
                    }
                } else {
                    if (plugin.getConfigManager().isDebug()) {
                        Object nickname = invoke(getNicknameMethod, wrapper);
                        plugin.getLogger().info("[checkAndProtect] " + nickname + " 无携带物且无缓存");
                    }
                }
            }

            // ====== 形态强制执行 ======
            // 每轮扫描都主动写回缓存的形态，防止 Pixelmon 游戏逻辑改掉它
            enforceForm(pokemon, key);

        } catch (Exception e) {
            plugin.getLogger().warning("[checkAndProtect] 异常: " + e.getMessage());
        }
    }

    /**
     * 获取宝可梦当前形态名称（从 pokemon.form.Stats.name 读取）
     */
    private String getFormName(Object pokemon) {
        // 方法1: 调用 pokemon.getForm() 获取形态对象
        if (pokemonGetFormMethod != null) {
            try {
                Object form = invoke(pokemonGetFormMethod, pokemon);
                if (form != null && statsNameField != null) {
                    Object name = statsNameField.get(form);
                    if (name != null) return name.toString();
                }
            } catch (Exception ignored) {}
        }
        // 方法2: 直接从 pokemon.form 字段读
        if (pokemonFormField != null) {
            try {
                Object form = pokemonFormField.get(pokemon);
                if (form != null && statsNameField != null) {
                    Object name = statsNameField.get(form);
                    if (name != null) return name.toString();
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    /**
     * 每轮扫描强制执行形态 — 如果缓存中有形态名称，且当前形态不同，就尝试恢复
     */
    private void enforceForm(Object pokemon, String key) {
        PokemonCache cached = protectedItems.get(key);
        if (cached == null || cached.formName == null || cached.formName.isEmpty()) {
            return;
        }

        String currentName = getFormName(pokemon);
        if (cached.formName.equals(currentName)) {
            return; // 形态正确，无需修改
        }

        // 形态被改了！尝试恢复
        plugin.getLogger().info("[形态] 检测到形态变化: " + currentName + " → " + cached.formName + "，尝试恢复");

        // 方式1: 调用 setForm() / setFormIndex() 等方法
        if (pokemonSetFormMethod != null) {
            try {
                Class<?>[] paramTypes = pokemonSetFormMethod.getParameterTypes();
                if (paramTypes.length == 1 && paramTypes[0] == String.class) {
                    invoke(pokemonSetFormMethod, pokemon, cached.formName);
                    plugin.getLogger().info("[形态] 通过 " + pokemonSetFormMethod.getName() + "(\"" + cached.formName + "\") 恢复成功");
                    return;
                } else if (paramTypes.length == 0) {
                    invoke(pokemonSetFormMethod, pokemon);
                    plugin.getLogger().info("[形态] 通过 " + pokemonSetFormMethod.getName() + "() 恢复");
                    return;
                }
            } catch (Exception e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("[形态] setForm 调用失败: " + e.getMessage());
                }
            }
        }

        // 方式2: 尝试从 species 获取所有形态并找到匹配的
        if (pokemonSpeciesField != null && speciesGetFormsMethod != null && pokemonFormField != null) {
            try {
                Object species = pokemonSpeciesField.get(pokemon);
                if (species != null) {
                    Object forms = invoke(speciesGetFormsMethod, species);
                    if (forms instanceof List) {
                        for (Object form : (List<?>) forms) {
                            if (form != null && statsNameField != null) {
                                Object name = statsNameField.get(form);
                                if (name != null && cached.formName.equals(name.toString())) {
                                    pokemonFormField.set(pokemon, form);
                                    plugin.getLogger().info("[形态] 通过 species.getForms() 找到并恢复形态: " + cached.formName);
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("[形态] species 查找失败: " + e.getMessage());
                }
            }
        }

        // 方式3: 兜底 — 如果 pokemon.form 字段还在，尝试直接设一个新的 Stats 对象
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().warning("[形态] 所有恢复方式均失败，formName=" + cached.formName);
        }
    }

    /**
     * 恢复携带物到宝可梦身上
     *
     * 注意：Pokemon.heldItem 是 ItemStack (Minecraft) 类型，
     * 而 PixelmonWrapper.getHeldItem() 返回的是 HeldItem 类型。
     * 形态恢复由 enforceForm() 每轮强制执行。
     */
    private boolean restoreHeldItem(Object pokemon, PokemonCache cache) {
        boolean itemRestored = false;

        // 方法1: 直接设置 pokemon.heldItem 字段 (ItemStack 类型)
        if (pokemonHeldItemField != null) {
            try {
                pokemonHeldItemField.set(pokemon, cache.itemStack);
                itemRestored = true;
                plugin.getLogger().info("[恢复] 通过 pokemon.heldItem 字段反射成功恢复");
            } catch (Exception e) {
                plugin.getLogger().warning("[恢复] 字段设置失败: " + e.getMessage());
            }
        }

        // 方法2: 调用 pokemon.setHeldItem(item) 方法
        if (!itemRestored && setPokemonHeldItemMethod != null) {
            try {
                invoke(setPokemonHeldItemMethod, pokemon, cache.itemStack);
                itemRestored = true;
                plugin.getLogger().info("[恢复] 通过 pokemon.setHeldItem() 成功恢复");
            } catch (Exception e) {
                plugin.getLogger().warning("[恢复] setHeldItem 调用失败: " + e.getMessage());
            }
        }

        return itemRestored;
    }

    // ========== 扫描辅助方法 ==========

    @SuppressWarnings("unchecked")
    private List<Object> getActiveBattles() {
        try {
            Class<?> registryClass = Class.forName(BATTLE_REGISTRY_CLASS);

            // 优先用静态方法
            if (getBattlesMethod != null) {
                Object result = getBattlesMethod.invoke(null);
                return toList(result);
            }

            // 备用: 静态字段
            if (battlesField != null) {
                Object result = battlesField.get(null);
                return toList(result);
            }

            return Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> getParticipants(Object controller) {
        try {
            if (getParticipantsMethod != null) {
                Object result = getParticipantsMethod.invoke(controller);
                return toList(result);
            }
            if (participantsField != null) {
                Object result = participantsField.get(controller);
                return toList(result);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> toList(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List) return (List<Object>) obj;
        if (obj instanceof Collection) return new ArrayList<>((Collection<Object>) obj);
        if (obj instanceof Map) return new ArrayList<>(((Map<Object, Object>) obj).values());
        if (obj instanceof Set) return new ArrayList<>((Set<Object>) obj);
        if (obj.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(obj); i++) {
                list.add(java.lang.reflect.Array.get(obj, i));
            }
            return list;
        }
        return null;
    }

    private Object extractWrapper(Object participant) {
        // 如果参与者本身就是 PixelmonWrapper
        if (participant.getClass().getName().equals(PIXELMON_WRAPPER_CLASS)) {
            return participant;
        }

        // 关键: 从 BattleParticipant 的 controlledPokemon 字段取当前出战的宝可梦
        // controlledPokemon 是 List<PixelmonWrapper>，当前出战的在第0个
        Object controlled = getFieldValue(participant, "controlledPokemon");
        if (controlled instanceof List) {
            List<?> list = (List<?>) controlled;
            if (!list.isEmpty()) {
                Object first = list.get(0);
                if (first != null && first.getClass().getName().equals(PIXELMON_WRAPPER_CLASS)) {
                    return first;
                }
            }
        }

        // 备用: 从 allPokemon 字段 (PixelmonWrapper[]) 取第一个
        Object all = getFieldValue(participant, "allPokemon");
        if (all instanceof Object[]) {
            Object[] arr = (Object[]) all;
            if (arr.length > 0 && arr[0] != null && arr[0].getClass().getName().equals(PIXELMON_WRAPPER_CLASS)) {
                return arr[0];
            }
        }

        // 最后备用: 遍历所有字段找 PixelmonWrapper
        Class<?> current = participant.getClass();
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(participant);
                    if (val != null && val.getClass().getName().equals(PIXELMON_WRAPPER_CLASS)) {
                        return val;
                    }
                } catch (Exception ignored) {}
            }
            current = current.getSuperclass();
        }

        return participant;
    }

    // ========== 工具方法 ==========

    /**
     * 检查目标宝可梦的道具是否在黑名单（对外暴露，供其他类使用）
     */
    public boolean isBlacklisted(Object targetWrapper) {
        try {
            if (targetWrapper == null) return false;

            Object hasItem = invoke(hasHeldItemMethod, targetWrapper);
            if (hasItem == null || !(Boolean) hasItem) return false;

            Object heldItem = invoke(getHeldItemMethod, targetWrapper);
            if (heldItem == null) return false;

            String itemId = getItemIdentifier(heldItem);
            return itemId != null && plugin.getConfigManager().isBlocked(itemId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取物品标识符
     */
    public String getItemIdentifier(Object heldItem) {
        try {
            // 尝试字段
            for (String name : new String[]{"itemType", "item", "heldItemType", "type", "name"}) {
                Field f = findFieldAny(heldItem.getClass(), name);
                if (f != null) {
                    Object val = f.get(heldItem);
                    if (val != null) {
                        String str = val.toString();
                        return str.contains(":") ? str.toLowerCase() : "pixelmon:" + str.toLowerCase();
                    }
                }
            }
            // 尝试无参方法
            for (String name : new String[]{"getName", "getLocalizedName", "getRegistryName"}) {
                Method m = findMethodAny(heldItem.getClass(), name);
                if (m != null) {
                    Object val = m.invoke(heldItem);
                    if (val != null) {
                        String str = val.toString();
                        return str.contains(":") ? str.toLowerCase() : "pixelmon:" + str.toLowerCase();
                    }
                }
            }
            return heldItem.toString().toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private String getPokemonKey(Object wrapper) {
        try {
            Object uuid = getFieldValue(wrapper, "uuid");
            if (uuid != null) return uuid.toString();
            Object pokemon = getFieldValue(wrapper, "pokemon");
            if (pokemon != null) {
                Object puuid = getFieldValue(pokemon, "uuid");
                if (puuid != null) return puuid.toString();
            }
            return String.valueOf(System.identityHashCode(wrapper));
        } catch (Exception e) {
            return String.valueOf(System.identityHashCode(wrapper));
        }
    }

    // ========== 反射工具 ==========

    private Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Method m = current.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Method findMethodAny(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Field findFieldAny(Class<?> clazz, String name) {
        return findField(clazz, name);
    }

    private Object invoke(Method method, Object instance, Object... args) {
        try {
            if (method == null) return null;
            return method.invoke(instance, args);
        } catch (Exception e) {
            return null;
        }
    }

    private Object getFieldValue(Object obj, String name) {
        if (obj == null) return null;
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }

    // ========== Getter ==========

    public Method getHasHeldItemMethod() { return hasHeldItemMethod; }
    public Method getGetHeldItemMethod() { return getHeldItemMethod; }
    public Method getGetNicknameMethod() { return getNicknameMethod; }
    public Field getHeldItemField() { return pokemonHeldItemField != null ? pokemonHeldItemField : heldItemField; }
    public Field getPokemonField() { return pokemonField; }
    public Field getEntityField() { return entityField; }
    public Method getPokemonGetFormMethod() { return pokemonGetFormMethod; }
    public Method getPokemonSetFormMethod() { return pokemonSetFormMethod; }
    public Method getSetPokemonHeldItemMethod() { return setPokemonHeldItemMethod; }
    public boolean isRunning() { return running; }
    public int getProtectedCount() { return protectedItems.size(); }
}

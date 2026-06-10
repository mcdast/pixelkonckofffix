package com.pixelknockoff.command;

import com.pixelknockoff.PixelKnockOffPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;

/**
 * 重载指令处理类
 * 指令: /pixelknockoff reload
 */
public class ReloadCommand implements CommandExecutor, TabCompleter {

    private final PixelKnockOffPlugin plugin;

    public ReloadCommand(PixelKnockOffPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("reload")) {
            if (!sender.hasPermission("pixelknockoff.reload")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此指令");
                return true;
            }

            try {
                plugin.reloadPlugin();
                int blockedCount = plugin.getConfigManager().getBlockedCount();
                sender.sendMessage(ChatColor.GREEN + "配置已重载");
                sender.sendMessage(ChatColor.GRAY + "黑名单道具数量: " + ChatColor.WHITE + blockedCount);
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "重载配置时发生错误: " + e.getMessage());
                plugin.getLogger().severe("重载配置失败: " + e.getMessage());
                e.printStackTrace();
            }
            return true;
        }

        if (subCommand.equals("list")) {
            if (!sender.hasPermission("pixelknockoff.list")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此指令");
                return true;
            }

            List<String> blockedItems = plugin.getConfigManager().getBlockedItems();
            sender.sendMessage(ChatColor.GOLD + "=== 拍落技能黑名单 ===");
            sender.sendMessage(ChatColor.GRAY + "共 " + blockedItems.size() + " 个道具");

            for (int i = 0; i < blockedItems.size(); i++) {
                sender.sendMessage(ChatColor.YELLOW + Integer.toString(i + 1) + ". " + blockedItems.get(i));
            }
            return true;
        }

        if (subCommand.equals("test")) {
            if (!sender.hasPermission("pixelknockoff.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此指令");
                return true;
            }
            runDiagnostic(sender);
            return true;
        }

        if (subCommand.equals("check")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "用法: /pixelknockoff check <道具ID>");
                return true;
            }

            if (!sender.hasPermission("pixelknockoff.check")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此指令");
                return true;
            }

            String itemId = args[1].toLowerCase();
            boolean blocked = plugin.getConfigManager().isBlocked(itemId);

            if (blocked) {
                sender.sendMessage(ChatColor.RED + "道具 " + itemId + " 在黑名单中 (会被保护)");
            } else {
                sender.sendMessage(ChatColor.GREEN + "道具 " + itemId + " 不在黑名单中 (可以被拍落)");
            }
            return true;
        }

        if (subCommand.equals("findbus")) {
            if (!sender.hasPermission("pixelknockoff.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此指令");
                return true;
            }
            runFindBus(sender);
            return true;
        }

        if (subCommand.equals("debugscan")) {
            if (!sender.hasPermission("pixelknockoff.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此指令");
                return true;
            }
            runDebugScan(sender);
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void runFindBus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== EventBus & BattleController 扫描 ===");
        
        // 扫描可能的 EventBus 类
        String[] possibleBusClasses = new String[]{
            "net.minecraftforge.eventbus.EventBus",
            "net.minecraftforge.eventbus.api.EventBus",
            "cpw.mods.modlauncher.api.IModLoader",
            "com.mojang.bridge.Bus"
        };
        
        for (String className : possibleBusClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                sender.sendMessage(ChatColor.GREEN + "[OK] " + className);
                
                // 查找静态字段
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getType().getModifiers())) {
                        if (clazz.isAssignableFrom(f.getType()) || f.getType().equals(clazz)) {
                            sender.sendMessage(ChatColor.GRAY + "  - 静态字段: " + f.getName() + " (" + f.getType().getSimpleName() + ")");
                        }
                    }
                }
                
                // 查找 getInstance 方法
                try {
                    java.lang.reflect.Method m = clazz.getMethod("getInstance");
                    sender.sendMessage(ChatColor.GRAY + "  - getInstance() 方法存在");
                } catch (NoSuchMethodException e) {
                    // 没有 getInstance
                }
                
            } catch (ClassNotFoundException e) {
                sender.sendMessage(ChatColor.RED + "[不存在] " + className);
            } catch (Exception e) {
                sender.sendMessage(ChatColor.YELLOW + "[错误] " + className + ": " + e.getMessage());
            }
        }
        
        // 扫描 Pixelmon 类中的 EventBus 引用
        sender.sendMessage(ChatColor.GOLD + "=== Pixelmon 类中的 EventBus 引用 ===");
        try {
            Class<?> pixelmonClass = Class.forName("com.pixelmonmod.pixelmon.Pixelmon");
            for (java.lang.reflect.Field f : pixelmonClass.getDeclaredFields()) {
                String typeName = f.getType().getName();
                if (typeName.contains("EventBus") || typeName.contains("Bus")) {
                    sender.sendMessage(ChatColor.GREEN + "[OK] Pixelmon." + f.getName() + " = " + typeName);
                }
            }
        } catch (ClassNotFoundException e) {
            sender.sendMessage(ChatColor.RED + "[不存在] com.pixelmonmod.pixelmon.Pixelmon");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.YELLOW + "[错误] " + e.getMessage());
        }
        
        // 扫描 BattleController 类
        sender.sendMessage(ChatColor.GOLD + "=== BattleController 类扫描 ===");
        String[] controllerClasses = {
            "com.pixelmonmod.pixelmon.battles.controller.BattleControllerBase",
            "com.pixelmonmod.pixelmon.battles.controller.BattleController",
            "com.pixelmonmod.pixelmon.battles.controller.BattleControllerAbstract",
            "com.pixelmonmod.pixelmon.battles.controller.BattleManager",
            "com.pixelmonmod.pixelmon.battles.controller.BattleControl",
            "com.pixelmonmod.pixelmon.battles.controller.BattleLogic"
        };
        for (String className : controllerClasses) {
            try {
                Class.forName(className);
                sender.sendMessage(ChatColor.GREEN + "[OK] " + className);
                
                try {
                    for (java.lang.reflect.Method m : Class.forName(className).getMethods()) {
                        if (m.getName().contains("articipant") || m.getName().contains("attle") || m.getName().contains("articip")) {
                            sender.sendMessage(ChatColor.GRAY + "  - " + m.getName() + "(" + m.getParameterCount() + " params)");
                        }
                    }
                } catch (Exception ignored) {}
            } catch (ClassNotFoundException e) {
                sender.sendMessage(ChatColor.RED + "[不存在] " + className);
            }
        }
        
        // 扫描 BattleRegistry 类
        sender.sendMessage(ChatColor.GOLD + "=== BattleRegistry 类扫描 ===");
        try {
            Class<?> regClass = Class.forName("com.pixelmonmod.pixelmon.battles.BattleRegistry");
            sender.sendMessage(ChatColor.GREEN + "[OK] 类存在");
            
            // 列出所有方法
            for (java.lang.reflect.Method m : regClass.getMethods()) {
                if (!m.getDeclaringClass().equals(Object.class)) {
                    sender.sendMessage(ChatColor.GRAY + "  [方法] " + m.getName() + "(" + m.getParameterCount() + " params) - " + m.getReturnType().getSimpleName());
                }
            }
            
            // 列出所有声明字段
            for (java.lang.reflect.Field f : regClass.getDeclaredFields()) {
                int mod = f.getModifiers();
                sender.sendMessage(ChatColor.GRAY + "  [字段] " + (java.lang.reflect.Modifier.isStatic(mod) ? "static " : "") + f.getType().getSimpleName() + " " + f.getName());
            }
        } catch (ClassNotFoundException e) {
            sender.sendMessage(ChatColor.RED + "[不存在] com.pixelmonmod.pixelmon.battles.BattleRegistry");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<String>();

        if (args.length == 1) {
            completions.add("reload");
            completions.add("list");
            completions.add("check");
            completions.add("findbus");
            completions.add("debugscan");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            // 提供一些常见的 Pixelmon 道具ID作为参考
            completions.add("pixelmon:adamant_crystal");
            completions.add("pixelmon:lustrous_globe");
            completions.add("pixelmon:griseous_core");
            completions.add("pixelmon:master_ball");
            completions.add("pixelmon:poke_ball");
        }

        // 过滤以输入开头的选项
        String input = args[args.length - 1].toLowerCase();
        List<String> filtered = new ArrayList<String>();
        for (String completion : completions) {
            if (completion.toLowerCase().startsWith(input)) {
                filtered.add(completion);
            }
        }
        return filtered;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== PixelKnockOff 指令帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/pixelknockoff reload" + ChatColor.GRAY + " - 重载配置文件");
        sender.sendMessage(ChatColor.YELLOW + "/pixelknockoff list" + ChatColor.GRAY + " - 查看黑名单列表");
        sender.sendMessage(ChatColor.YELLOW + "/pixelknockoff check <道具ID>" + ChatColor.GRAY + " - 检查道具是否在黑名单");
        sender.sendMessage(ChatColor.YELLOW + "/pixelknockoff test" + ChatColor.GRAY + " - 诊断事件类");
        sender.sendMessage(ChatColor.YELLOW + "/pixelknockoff debugscan" + ChatColor.GRAY + " - 扫描战斗诊断");
    }

    /**
     * 战斗扫描诊断 - 手动执行扫描链并输出每步结果
     */
    private void runDebugScan(CommandSender sender) {
        com.pixelknockoff.interceptor.KnockOffInterceptor interceptor = plugin.getKnockOffInterceptor();
        if (interceptor == null) {
            sender.sendMessage(ChatColor.RED + "拦截器未初始化");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== 战斗扫描诊断 ===");

        // 1. 获取 BATTLES_BY_ID
        try {
            Class<?> registryClass = Class.forName("com.pixelmonmod.pixelmon.battles.BattleRegistry");
            java.lang.reflect.Field battlesField = registryClass.getDeclaredField("BATTLES_BY_ID");
            battlesField.setAccessible(true);
            Object battlesMap = battlesField.get(null);
            sender.sendMessage(ChatColor.YELLOW + "[BATTLES_BY_ID] 类型: " + (battlesMap != null ? battlesMap.getClass().getName() : "null"));

            if (battlesMap instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) battlesMap;
                sender.sendMessage(ChatColor.YELLOW + "[BATTLES_BY_ID] 大小: " + map.size());
                if (map.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "[BATTLES_BY_ID] 为空，没有活跃战斗！");
                    return;
                }
                int idx = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object controller = entry.getValue();
                    sender.sendMessage(ChatColor.GRAY + "  战斗#" + idx + " key=" + entry.getKey() + " 控制器=" + (controller != null ? controller.getClass().getName() : "null"));
                    if (controller != null) {
                        // 获取参与者
                        java.lang.reflect.Field partField = findDeclaredField(controller.getClass(), "participants");
                        if (partField != null) {
                            partField.setAccessible(true);
                            Object participants = partField.get(controller);
                            sender.sendMessage(ChatColor.GRAY + "    participants类型: " + (participants != null ? participants.getClass().getName() : "null"));
                            sender.sendMessage(ChatColor.GRAY + "    participants内容: " + participants);
                            if (participants != null) {
                                List<?> list = toListStatic(participants);
                                if (list != null) {
                                    sender.sendMessage(ChatColor.GRAY + "    participants列表大小: " + list.size());
                                    for (int pi = 0; pi < list.size(); pi++) {
                                        Object p = list.get(pi);
                                        sender.sendMessage(ChatColor.GRAY + "      参与者#" + pi + " 类型=" + (p != null ? p.getClass().getName() : "null") + " toString=" + p);
                                        if (p != null) {
                                             // 打印参与者所有字段
                                             sender.sendMessage(ChatColor.GRAY + "      参与者类: " + p.getClass().getName());
                                             sender.sendMessage(ChatColor.GRAY + "      所有字段:");
                                             Class<?> pClass = p.getClass();
                                             while (pClass != null && pClass != Object.class) {
                                                 for (java.lang.reflect.Field f : pClass.getDeclaredFields()) {
                                                     try {
                                                         f.setAccessible(true);
                                                         Object val = f.get(p);
                                                         String valStr;
                                                         if (val == null) {
                                                             valStr = "null";
                                                         } else {
                                                             String vClass = val.getClass().getName();
                                                             valStr = vClass + "@" + Integer.toHexString(System.identityHashCode(val));
                                                             if (vClass.contains("String") || vClass.contains("Integer") || vClass.contains("Boolean")) {
                                                                 valStr = val.toString();
                                                             }
                                                         }
                                                         sender.sendMessage(ChatColor.GRAY + "        " + pClass.getSimpleName() + "." + f.getType().getSimpleName() + " " + f.getName() + " = " + valStr);
                                                     } catch (Exception ignored) {}
                                                 }
                                                 pClass = pClass.getSuperclass();
                                             }
                                             
                                             // 尝试提取 PokemonWrapper (使用 controlledPokemon 方法，和拦截器保持一致)
                                            Object wrapper = null;
                                            if (p.getClass().getName().contains("PixelmonWrapper")) {
                                                wrapper = p;
                                            } else {
                                                // 从 controlledPokemon 取第0个
                                                Object controlled = getFieldValueStatic(p, "controlledPokemon");
                                                if (controlled instanceof List) {
                                                    List<?> controlledList = (List<?>) controlled;
                                                    if (!controlledList.isEmpty()) {
                                                        wrapper = controlledList.get(0);
                                                    }
                                                }
                                                if (wrapper == null) {
                                                    // 备用: allPokemon
                                                    Object allPokemon = getFieldValueStatic(p, "allPokemon");
                                                    if (allPokemon instanceof Object[]) {
                                                        Object[] arr = (Object[]) allPokemon;
                                                        if (arr.length > 0) wrapper = arr[0];
                                                    }
                                                }
                                                if (wrapper == null) {
                                                    wrapper = getFieldValueStatic(p, "pokemon");
                                                }
                                            }
                                            if (wrapper != null) {
                                                boolean isWrapper = wrapper.getClass().getName().contains("PixelmonWrapper");
                                                sender.sendMessage(ChatColor.GRAY + "      wrapper类型=" + wrapper.getClass().getName() + " isWrapper=" + isWrapper);

                                                // ====== 打印 PixelmonWrapper 所有字段 ======
                                                sender.sendMessage(ChatColor.GRAY + "      === PixelmonWrapper 所有字段 ===");
                                                Class<?> wClass = wrapper.getClass();
                                                while (wClass != null && wClass != Object.class) {
                                                    for (java.lang.reflect.Field f : wClass.getDeclaredFields()) {
                                                        try {
                                                            f.setAccessible(true);
                                                            Object val = f.get(wrapper);
                                                            String valStr;
                                                            if (val == null) {
                                                                valStr = "null";
                                                            } else {
                                                                String vClass = val.getClass().getName();
                                                                valStr = vClass + "@" + Integer.toHexString(System.identityHashCode(val));
                                                                if (vClass.contains("String") || vClass.contains("Integer") || vClass.contains("Boolean") || vClass.contains("Item") || vClass.contains("Held")) {
                                                                    valStr = val.toString();
                                                                }
                                                            }
                                                            sender.sendMessage(ChatColor.GRAY + "        " + wClass.getSimpleName() + "." + f.getType().getSimpleName() + " " + f.getName() + " = " + valStr);
                                                        } catch (Exception ignored) {}
                                                    }
                                                    wClass = wClass.getSuperclass();
                                                }

                                                // 检查 hasHeldItem
                                                Object hasItem = interceptor.getHasHeldItemMethod() != null ?
                                                    invokeStatic(interceptor.getHasHeldItemMethod(), wrapper) : "N/A";
                                                sender.sendMessage(ChatColor.GRAY + "      hasHeldItem=" + hasItem);

                                                // 获取携带物
                                                Object heldItem = interceptor.getGetHeldItemMethod() != null ?
                                                    invokeStatic(interceptor.getGetHeldItemMethod(), wrapper) : "N/A";
                                                sender.sendMessage(ChatColor.GRAY + "      getHeldItem=" + heldItem);

                                                if (heldItem != null && !(heldItem instanceof String)) {
                                                    String itemId = interceptor.getItemIdentifier(heldItem);
                                                    sender.sendMessage(ChatColor.GRAY + "      itemId=" + itemId);
                                                    boolean blocked = itemId != null && plugin.getConfigManager().isBlocked(itemId);
                                                    sender.sendMessage(ChatColor.GRAY + "      是否在黑名单=" + blocked);
                                                }

                                                // 检查 pokemon.heldItem 字段 (ItemStack)
                                                java.lang.reflect.Field heldField = interceptor.getHeldItemField();
                                                if (heldField != null) {
                                                    Object pokemon = getFieldValueStatic(wrapper, "pokemon");
                                                    if (pokemon != null) {
                                                        Object fieldVal = heldField.get(pokemon);
                                                        sender.sendMessage(ChatColor.GRAY + "      pokemon.heldItem字段(ItemStack)=" + fieldVal);
                                                    } else {
                                                        sender.sendMessage(ChatColor.GRAY + "      无法获取pokemon对象");
                                                    }
                                                }

                                                // 形态诊断
                                                sender.sendMessage(ChatColor.GRAY + "      === 形态诊断 ===");
                                                Object entity = getFieldValueStatic(wrapper, "entity");
                                                if (entity != null) {
                                                    // 打印 entity 类中 form 字段的值
                                                    for (String fName : new String[]{"form", "pokemonForm", "displayForm", "currentForm"}) {
                                                        try {
                                                            java.lang.reflect.Field ff = findDeclaredField(entity.getClass(), fName);
                                                            if (ff != null) {
                                                                Object fv = ff.get(entity);
                                                                sender.sendMessage(ChatColor.GRAY + "      entity." + fName + "=" + fv);
                                                            }
                                                        } catch (Exception ignored) {}
                                                    }
                                                    // 打印 Pokemon 中 form 字段
                                                    Object pokemon = getFieldValueStatic(wrapper, "pokemon");
                                                    if (pokemon != null) {
                                                        for (String fName : new String[]{"form", "pokemonForm", "displayForm"}) {
                                                            try {
                                                                java.lang.reflect.Field ff = findDeclaredField(pokemon.getClass(), fName);
                                                                if (ff != null) {
                                                                    Object fv = ff.get(pokemon);
                                                                    sender.sendMessage(ChatColor.GRAY + "      pokemon." + fName + "=" + fv);
                                                                }
                                                            } catch (Exception ignored) {}
                                                        }
                                                    }
                                                }

                                                // 昵称
                                                Object nickname = interceptor.getGetNicknameMethod() != null ?
                                                    invokeStatic(interceptor.getGetNicknameMethod(), wrapper) : "N/A";
                                                sender.sendMessage(ChatColor.GRAY + "      getNickname=" + nickname);
                                            } else {
                                                sender.sendMessage(ChatColor.RED + "      无法提取 wrapper！");
                                            }
                                        }
                                    }
                                } else {
                                    sender.sendMessage(ChatColor.RED + "      toList 返回 null");
                                }
                            }
                        } else {
                            sender.sendMessage(ChatColor.RED + "    未找到 participants 字段！");
                            // 列出所有字段
                            sender.sendMessage(ChatColor.GRAY + "    控制器所有字段:");
                            for (java.lang.reflect.Field f : controller.getClass().getDeclaredFields()) {
                                sender.sendMessage(ChatColor.GRAY + "      " + f.getType().getSimpleName() + " " + f.getName());
                            }
                        }
                    }
                    idx++;
                    if (idx >= 3) {
                        sender.sendMessage(ChatColor.GRAY + "  ... (仅显示前3场战斗)");
                        break;
                    }
                }
            } else {
                sender.sendMessage(ChatColor.RED + "BATTLES_BY_ID 不是 Map 类型！实际=" + (battlesMap != null ? battlesMap.getClass().getName() : "null"));
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "诊断异常: " + e.getMessage());
            e.printStackTrace();
        }

        sender.sendMessage(ChatColor.GOLD + "=== 诊断完成 ===");
        sender.sendMessage(ChatColor.GRAY + "受保护缓存大小: " + interceptor.getProtectedCount());
    }

    /**
     * 递归查找字段
     */
    private java.lang.reflect.Field findDeclaredField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                java.lang.reflect.Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 获取字段值
     */
    private Object getFieldValueStatic(Object obj, String name) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = findDeclaredField(obj.getClass(), name);
            if (f != null) {
                f.setAccessible(true);
                return f.get(obj);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 调用方法
     */
    private Object invokeStatic(java.lang.reflect.Method method, Object instance, Object... args) {
        try {
            if (method == null) return null;
            return method.invoke(instance, args);
        } catch (Exception e) {
            return "异常: " + e.getMessage();
        }
    }

    /**
     * 转 List
     */
    @SuppressWarnings("unchecked")
    private List<?> toListStatic(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List) return (List<?>) obj;
        if (obj instanceof Collection) return new ArrayList<>((Collection<?>) obj);
        if (obj instanceof Map) return new ArrayList<>(((Map<?, ?>) obj).values());
        if (obj instanceof Set) return new ArrayList<>((Set<?>) obj);
        if (obj.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(obj); i++) {
                list.add(java.lang.reflect.Array.get(obj, i));
            }
            return list;
        }
        return null;
    }

    /**
     * 诊断指令 - 查找Pixelmon事件类
     */
    private void runDiagnostic(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== PixelKnockOff 诊断 ===");

        // 1. 检查关键类
        String[] checkClasses = {
            "com.pixelmonmod.pixelmon.battles.controller.participants.PixelmonWrapper",
            "com.pixelmonmod.pixelmon.battles.attacks.specialAttacks.basic.KnockOff",
            "com.pixelmonmod.pixelmon.battles.attacks.Attack",
            "com.pixelmonmod.pixelmon.enums.heldItems.EnumHeldItems",
            "net.minecraftforge.eventbus.EventBus",
            "net.minecraftforge.eventbus.api.IEventBus",
            "com.pixelmonmod.pixelmon.Pixelmon"
        };

        sender.sendMessage(ChatColor.YELLOW + "--- 关键类检查 ---");
        for (String className : checkClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                sender.sendMessage(ChatColor.GREEN + "[OK] " + className);
            } catch (ClassNotFoundException e) {
                sender.sendMessage(ChatColor.RED + "[FAIL] " + className);
            }
        }

        // 2. 扫描Pixelmon事件类
        sender.sendMessage(ChatColor.YELLOW + "--- Pixelmon事件类 ---");
        String[] eventPrefixes = {
            "com.pixelmonmod.pixelmon.api.events.",
            "com.pixelmonmod.pixelmon.api.events.battles.",
            "com.pixelmonmod.pixelmon.api.events.pokemon.",
            "com.pixelmonmod.pixelmon.battles.events.",
            "com.pixelmonmod.pixelmon.events."
        };

        try {
            // 扫描服务端目录中所有Jar包
            java.io.File serverDir = plugin.getServer().getWorldContainer().getAbsoluteFile().getParentFile();
            java.util.List<java.io.File> jars = findAllJars(serverDir);

            for (String prefix : eventPrefixes) {
                int count = 0;
                String prefixPath = prefix.replace('.', '/');
                for (java.io.File jarFile : jars) {
                    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                        java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            java.util.jar.JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.endsWith(".class") && name.startsWith(prefixPath) && !name.contains("$")) {
                                sender.sendMessage(ChatColor.AQUA + "  " + name.replace('/', '.').replace(".class", ""));
                                count++;
                                if (count > 30) {
                                    sender.sendMessage(ChatColor.GRAY + "  ... 截断");
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // 跳过不可读Jar
                    }
                    if (count > 30) break;
                }
                if (count == 0) {
                    sender.sendMessage(ChatColor.GRAY + "  (无匹配类) " + prefix);
                }
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "扫描错误: " + e.getMessage());
        }

        sender.sendMessage(ChatColor.GOLD + "=== 诊断完成 ===");
        sender.sendMessage(ChatColor.GRAY + "请将以上输出截图/复制给开发者");
    }

    /**
     * 递归查找所有Jar包
     */
    private java.util.List<java.io.File> findAllJars(java.io.File dir) {
        java.util.List<java.io.File> jars = new java.util.ArrayList<java.io.File>();
        if (dir == null || !dir.exists()) return jars;
        java.io.File[] files = dir.listFiles();
        if (files == null) return jars;
        for (java.io.File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                jars.add(file);
            } else if (file.isDirectory() && !file.getName().equals("cache") && !file.getName().equals("crash-reports") && !file.getName().equals("logs")) {
                jars.addAll(findAllJars(file));
            }
        }
        return jars;
    }
}

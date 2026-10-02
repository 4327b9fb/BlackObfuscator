package top.niunaijun.obfuscator.chain;

import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.Trap;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.InvokeExpr;
import com.googlecode.dex2jar.ir.expr.Local;
import com.googlecode.dex2jar.ir.expr.Value;
import com.googlecode.dex2jar.ir.stmt.LabelStmt;
import com.googlecode.dex2jar.ir.stmt.LookupSwitchStmt;
import com.googlecode.dex2jar.ir.stmt.Stmt;
import com.googlecode.dex2jar.ir.stmt.Stmts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import top.niunaijun.obfuscator.LBlock;
import top.niunaijun.obfuscator.ObfuscatorConfiguration;
import top.niunaijun.obfuscator.RebuildIfResult;
import top.niunaijun.obfuscator.chain.base.BaseObfuscatorChain;

/**
 * 控制流平坦化混淆（flow flattening）。
 * <p>
 * 相对原版的关键修复：
 * 1. 调度器逐行对齐原版「String key + hashCode() 单层异或」设计（obf_index_final ^
 * obf_str.hashCode() 驱动 lookupswitch，状态词为 ObfDic 词典多词拼接长串）。
 * 另修原版 lookupswitch 键未排序的问题（JVMS 6.5 要求升序，否则 D8/R8 报
 * NegativeArraySizeException / Bad lookupswitch instruction）。
 * 2. 不再重排 local 的 _ls_index（原实现会把 T_unssa 已分配的寄存器索引改乱，
 * 是运行时 VerifyError / 寄存器冲突的隐患来源）。
 * 3. 修复原实现中「块语句重复注入」的问题（addAll 被调用两次，产生 return 后死代码）。
 * 4. 跳过条件扩展：含 new / 递归调用 / 嵌套 switch 的方法回退原样（R8 8.3.37/8.7.18
 * 类型推断与 IR 解析会崩溃，实测 Invalid descriptor char / NegativeArraySizeException）。
 */
public class FlowObfuscator extends BaseObfuscatorChain {

    public FlowObfuscator(ObfuscatorConfiguration obfuscatorConfiguration) {
        super(obfuscatorConfiguration);
    }

    @Override
    public boolean canDepth() {
        return false;
    }

    @Override
    public boolean canHandle(IrMethod ir, Stmt stmt) {
        return true;
    }

    @Override
    public RebuildIfResult reBuild0(IrMethod ir, Stmt stmt, List<Stmt> origStmts) {
        return null;
    }

    @Override
    public void reBuildEnd(IrMethod ir, List<Stmt> newStmts, List<Stmt> origStmts) {
        // 含 new / invoke-new 的方法经 Flow 状态机重排会破坏对象类型局部变量的
        // 寄存器类型安全（与参数共享物理寄存器、活跃区间跨 case，ART 校验失败），
        // 此类方法整体回退原样（Sub/If 链仍会执行）。
        if (ir.traps.size() > 0 || ir.name.equals("<init>")
                || containsNewExpr(ir) || containsRecursiveCall(ir) || hasSwitch(ir)) {
            newStmts.addAll(origStmts);
            return;
        }
        List<Stmt> orig = new ArrayList<>(origStmts);
        boolean existCallSuper = false;
        List<Stmt> tmp = new ArrayList<>();
        int max = 3;
        for (int i = 0; i < max; i++) {
            if (i > orig.size() - 1) {
                break;
            }
            Stmt stmt = orig.get(i);
            if (stmt.st == Stmt.ST.IDENTITY) {
                max++;
            }
            tmp.add(stmt);
            existCallSuper = isCallSuper(stmt);
            if (existCallSuper) {
                break;
            }
        }
        if (!existCallSuper) {
            orig = new ArrayList<>(origStmts);
        } else {
            newStmts.addAll(tmp);
            for (Stmt stmt : tmp) {
                orig.remove(stmt);
            }
        }

        // 字符串哈希调度状态机（逐行对齐原版 CodingGay/BlackObfuscator FlowObfuscator）：
        //   String obf_str = <ObfDic 多词拼接>; while(true) {
        //     obf_hash = obf_str.hashCode();
        //     switch(obf_index_final ^ obf_hash) { case <词.hashCode() ^ seed>: ...; obf_str = 下一词; ... } }
        Local obfIndexFinal = newLocal("obf_index_final", "I");
        Local obfIndex = newLocal("obf_index", "I");
        Local obfStrHash = newLocal("obf_hash", "I");
        Local obfStr = newLocal("obf_str", "Ljava/lang/String;");
        final Set<String> usedKeys = new HashSet<>();

        LBlock whileBlock = generateLBlock();

        List<LBlock> origBlocks = getAllBlock(ir.traps, orig);
        Map<Stmt, LBlock> origBlockMaps = new HashMap<>();
        for (LBlock origBlock : origBlocks) {
            origBlockMaps.put(origBlock.getLabelStmt(), origBlock);
        }

        Set<Stmt> exceptionBlock = new HashSet<>();
        for (LBlock origBlock : origBlocks) {
            if (origBlock.getTrap() != null) {
                exceptionBlock.addAll(Arrays.asList(origBlock.getTrap().handlers));
            }
        }

        List<LBlock> newBlocks = new LinkedList<>();
        LBlock lastBlock = null;
        for (LBlock lBlock : origBlocks) {
            String label = null;
            List<LBlock> tmpBlocks = new ArrayList<>();
            if (!exceptionBlock.contains(lBlock.getLabelStmt())) {
                for (Stmt stmt : lBlock.getStmts()) {
                    if (isCallSuper(stmt)) {
                        if (lastBlock != null) {
                            lastBlock.getStmts().add(stmt);
                            continue;
                        }
                    }
                    LBlock newBlock = new LBlock();
                    newBlock.getStmts().add(stmt);
                    newBlock.setKey(randomKeyStr(usedKeys));
                    newBlock.setTrap(lBlock.getTrap());
                    tmpBlocks.add(newBlock);
                    if (label == null) {
                        label = newBlock.getKey();
                    }
                    if (lastBlock != null) {
                        lastBlock.setNextKey(newBlock.getKey());
                    }
                    lastBlock = newBlock;
                }
                newBlocks.addAll(tmpBlocks);

                if (label != null) {
                    lBlock.getStmts().clear();
                    lBlock.getStmts().add(Stmts.nAssign(obfStr, Exprs.nString(label)));
                    lBlock.getStmts().add(Stmts.nGoto(whileBlock.getLabelStmt()));
                }
            }
        }

        if (newBlocks.isEmpty()) {
            newStmts.addAll(origStmts);
            return;
        }
        LBlock defaultTarget = generateLBlock();
        defaultTarget.getStmts().add(Stmts.nGoto(whileBlock.getLabelStmt()));

        LBlock enter = newBlocks.get(0);
        // 原版：seed = randomString(depth).hashCode()（随机词典串的哈希作为异或种子）
        int obfIndexI = randomString(depth).hashCode();

        newStmts.add(Stmts.nAssign(obfIndexFinal, Exprs.nInt(obfIndexI)));
        newStmts.add(Stmts.nAssign(obfStr, Exprs.nString(enter.getKey())));
        newStmts.add(whileBlock.getLabelStmt());

        newStmts.add(Stmts.nAssign(obfStrHash, hashInvoke(obfStr)));
        newStmts.add(Stmts.nAssign(obfIndex, Exprs.nXor(obfIndexFinal, obfStrHash, "I")));

        Map<Integer, LabelStmt> switchBlock = new LinkedHashMap<>();
        for (LBlock newBlock : newBlocks) {
            switchBlock.put(newBlock.getKey().hashCode() ^ obfIndexI, newBlock.getLabelStmt());
        }
        // lookupswitch 的 match 值必须升序（JVMS 6.5），否则类文件非法：
        // JVM 验证器报 "Bad lookupswitch instruction"，D8/R8 解析时也会触发
        // NegativeArraySizeException（issues #19/#24 的 VerifyError 同源）。
        List<Integer> sortedKeys = new ArrayList<>(switchBlock.keySet());
        Collections.sort(sortedKeys);
        int[] keys = new int[sortedKeys.size()];
        LabelStmt[] targets = new LabelStmt[sortedKeys.size()];
        for (int i = 0; i < sortedKeys.size(); i++) {
            keys[i] = sortedKeys.get(i);
            targets[i] = switchBlock.get(keys[i]);
        }
        LookupSwitchStmt lookupSwitchStmt = Stmts.nLookupSwitch(obfIndex,
                keys, targets,
                defaultTarget.getLabelStmt());

        newStmts.add(lookupSwitchStmt);

        newStmts.add(defaultTarget.getLabelStmt());
        newStmts.addAll(defaultTarget.getStmts());

        for (LBlock newBlock : newBlocks) {
            newStmts.add(newBlock.getLabelStmt());
            newStmts.addAll(newBlock.getStmts());

            if (newBlock.getNextKey() != null && newBlock.getNextKey().length() != 0) {
                Stmt stmt = newBlock.getStmts().get(newBlock.getStmts().size() - 1);
                if (stmt.st != Stmt.ST.GOTO && stmt.st != Stmt.ST.RETURN && stmt.st != Stmt.ST.THROW) {
                    newStmts.add(Stmts.nAssign(obfStr, Exprs.nString(newBlock.getNextKey())));
                    newStmts.add(Stmts.nGoto(whileBlock.getLabelStmt()));
                }
            }
        }

        for (LBlock origBlock : origBlocks) {
            newStmts.add(origBlock.getLabelStmt());
            if (!exceptionBlock.contains(origBlock.getLabelStmt())) {
                newStmts.addAll(origBlock.getStmts());
            } else {
                newStmts.add(Stmts.nAssign(obfStr, obfStr));
            }
        }

        // 嵌套状态机守卫：若 Flow 产物出现"一个 lookupswitch 位于另一个 lookupswitch 的
        // case 目标区间内"（嵌套状态机），R8 8.3.37/8.7.18 解析会报 Invalid descriptor
        // char（实测 AndroidX DrawableUtils.canSafelyMutateDrawable：25-case 外层内嵌
        // 5-case，两个 R8 版本必崩）。此时回退原方法（Sub/If 链仍会执行）。
        if (hasNestedSwitch(newStmts)) {
            newStmts.clear();
            newStmts.addAll(origStmts);
        }
    }

    /**
     * 原版状态密钥 = randomString(depth)：ObfDic 词典多词拼接的长串
     * （长度 (nextInt(5)+5)*depth，depth=1 时 5~9 个词），去重防 lookupswitch
     * match 碰撞（不同 key 同 hashCode 概率极低，仍兜底）。
     */
    private String randomKeyStr(Set<String> usedKeys) {
        String w;
        do {
            w = randomString(1);
        } while (!usedKeys.add(w));
        return w;
    }

    private static boolean containsNewExpr(IrMethod ir) {
        // new / invoke-new 表达式经 Flow 重排会破坏对象类型局部变量的寄存器类型
        // 安全（见 reBuildEnd 注释），此类方法整体回退原样。
        Set<Value> seen = new HashSet<>();
        for (Stmt s : ir.stmts) {
            if (newExprValue(s.getOp(), seen) || newExprValue(s.getOp1(), seen)
                    || newExprValue(s.getOp2(), seen)) {
                return true;
            }
            Value[] ops = s.getOps();
            if (ops != null) {
                for (Value v : ops) {
                    if (newExprValue(v, seen)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean newExprValue(Value v, Set<Value> seen) {
        if (v == null || !seen.add(v)) {
            return false;
        }
        if (v.vt == Value.VT.NEW || v.vt == Value.VT.INVOKE_NEW) {
            return true;
        }
        if (newExprValue(v.getOp1(), seen) || newExprValue(v.getOp2(), seen)) {
            return true;
        }
        Value[] ops = v.getOps();
        if (ops != null) {
            for (Value o : ops) {
                if (newExprValue(o, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsRecursiveCall(IrMethod ir) {
        // 递归方法（方法体直接/间接调用自身）经 Flow 重排后，R8 8.3.37/8.7.18 构建
        // IR 时会抛 Invalid descriptor char（实测 DrawableUtils.canSafelyMutateDrawable
        // 单层状态机必崩；同批白名单无递归方法 0 错误）。
        // 递归调用在 d2j IR 中以 Expr（StaticFieldExpr/MethodInvokeExpr 包装）出现，
        // 需要递归遍历所有值（含隐藏链），与 containsNew 相同路径。
        Set<Value> seen = new HashSet<>();
        for (Stmt s : ir.stmts) {
            if (recursiveCallValue(s.getOp(), ir, seen) || recursiveCallValue(s.getOp1(), ir, seen)
                    || recursiveCallValue(s.getOp2(), ir, seen)) {
                return true;
            }
            Value[] ops = s.getOps();
            if (ops != null) {
                for (Value v : ops) {
                    if (recursiveCallValue(v, ir, seen)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean recursiveCallValue(Value v, IrMethod ir, Set<Value> seen) {
        if (v == null || !seen.add(v)) {
            return false;
        }
        if (v instanceof InvokeExpr) {
            InvokeExpr inv = (InvokeExpr) v;
            if (inv.getOwner().equals(ir.owner) && inv.getName().equals(ir.name)) {
                return true;
            }
        }
        if (recursiveCallValue(v.getOp1(), ir, seen) || recursiveCallValue(v.getOp2(), ir, seen)) {
            return true;
        }
        Value[] ops = v.getOps();
        if (ops != null) {
            for (Value o : ops) {
                if (recursiveCallValue(o, ir, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNestedSwitch(List<Stmt> stmts) {
        List<LookupSwitchStmt> sws = new ArrayList<>();
        for (Stmt s : stmts) {
            if (s instanceof LookupSwitchStmt) {
                sws.add((LookupSwitchStmt) s);
            }
        }
        if (sws.size() < 2) {
            return false;
        }
        Map<LabelStmt, Integer> labelIdx = new HashMap<>();
        for (int i = 0; i < stmts.size(); i++) {
            Stmt s = stmts.get(i);
            if (s instanceof LabelStmt) {
                labelIdx.put((LabelStmt) s, i);
            }
        }
        for (LookupSwitchStmt s : sws) {
            int lo = Integer.MAX_VALUE;
            int hi = -1;
            for (LabelStmt t : s.targets) {
                Integer j = labelIdx.get(t);
                if (j != null) {
                    lo = Math.min(lo, j);
                    hi = Math.max(hi, j);
                }
            }
            Integer dj = labelIdx.get(s.defaultTarget);
            if (dj != null) {
                lo = Math.min(lo, dj);
                hi = Math.max(hi, dj);
            }
            int own = stmts.indexOf(s);
            for (LookupSwitchStmt s2 : sws) {
                if (s == s2) {
                    continue;
                }
                int j2 = stmts.indexOf(s2);
                if (j2 > lo && j2 < hi && j2 != own) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasSwitch(IrMethod ir) {
        for (Stmt s : ir.stmts) {
            if (s instanceof LookupSwitchStmt) {
                return true;
            }
        }
        return false;
    }

    /**
     * 原版 hashInvoke：str.hashCode() 的 InvokeExpr（运行时状态词哈希）。
     */
    private InvokeExpr hashInvoke(Local strLocal) {
        return Exprs.nInvokeVirtual(new Value[]{strLocal}, "Ljava/lang/String;", "hashCode", new String[0], "I");
    }

    private boolean isCallSuper(Stmt stmt) {
        if (stmt.st == Stmt.ST.VOID_INVOKE) {
            Stmt.E1Stmt e1Stmt = (Stmt.E1Stmt) stmt;
            InvokeExpr expr = (InvokeExpr) e1Stmt.getOp();
            return "<init>".equals(expr.method.getName());
        }
        return false;
    }

    private LBlock generateLBlock() {
        return new LBlock();
    }

    private List<LBlock> getAllBlock(List<Trap> traps, List<Stmt> origStmts) {
        List<LBlock> blocks = new ArrayList<>();
        LBlock lBlock = new LBlock();
        Trap trap = null;
        for (Stmt origStmt : origStmts) {
            if (origStmt.st == Stmt.ST.LABEL) {
                for (Trap t : traps) {
                    if (t.start == origStmt || t.end == origStmt) {
                        trap = t;
                        break;
                    } else {
                        trap = null;
                    }
                }
                blocks.add(lBlock);
                lBlock = new LBlock((LabelStmt) origStmt);
                if (trap != null) {
                    lBlock.setTrap(trap);
                }
            } else {
                lBlock.getStmts().add(origStmt);
            }
        }
        blocks.add(lBlock);
        return blocks;
    }
}

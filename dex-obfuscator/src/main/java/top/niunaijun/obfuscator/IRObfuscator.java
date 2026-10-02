package top.niunaijun.obfuscator;

import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.expr.Constant;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.Local;
import com.googlecode.dex2jar.ir.expr.Value;
import com.googlecode.dex2jar.ir.stmt.AssignStmt;
import com.googlecode.dex2jar.ir.stmt.LookupSwitchStmt;
import com.googlecode.dex2jar.ir.stmt.Stmt;
import com.googlecode.dex2jar.ir.stmt.StmtList;
import com.googlecode.dex2jar.ir.stmt.Stmts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import top.niunaijun.obfuscator.chain.FlowObfuscator;
import top.niunaijun.obfuscator.chain.IfObfuscator;
import top.niunaijun.obfuscator.chain.SubObfuscator;
import top.niunaijun.obfuscator.chain.base.ObfuscatorChain;

public class IRObfuscator {

    private static IRObfuscator obfuscator;
    private final ObfuscatorConfiguration configuration;
    private final List<ObfuscatorChain> chains;

    public static IRObfuscator get(ObfuscatorConfiguration obf) {
        if (obf == null) {
            // dex2jar 纯转换路径：直接返回无链实例，不污染单例缓存
            return new IRObfuscator(null);
        }
        if (obfuscator == null) {
            synchronized (IRObfuscator.class) {
                if (obfuscator == null) {
                    obfuscator = new IRObfuscator(obf);
                }
            }
        }
        return obfuscator;
    }

    public IRObfuscator(ObfuscatorConfiguration configuration) {
        this.configuration = configuration;
        this.chains = new LinkedList<>();
        if (configuration == null) {
            // dex2jar 纯转换路径（IR2JConverter 默认 obf=null）不应挂混淆链，
            // 否则构造链时 getObfDepth() 会 NPE（原版遗留缺陷，测试全量触发）。
            return;
        }
        // 调试用：-Dobf.chain.mask=1|2|4 只启用部分链（1=Flow, 2=Sub, 4=If）
        // 默认 7 = Flow+Sub+If。注意 6（110b）&1==0，会漏掉 Flow（字符串哈希调度），
        // 只有 Sub+If；启用 Flow 才能得到 switch(str.hashCode()^K) 的字符串调度效果。
        int mask = Integer.getInteger("obf.chain.mask", 7);
        if ((mask & 1) != 0) {
            this.chains.add(new FlowObfuscator(configuration));
        }
        if ((mask & 2) != 0) {
            this.chains.add(new SubObfuscator(configuration));
        }
        if ((mask & 4) != 0) {
            this.chains.add(new IfObfuscator(configuration));
        }
    }

    public void reBuildInstructions(IrMethod ir) {
        if (configuration == null)
            return;
        if (!configuration.accept(ir.owner, ir.name)) {
            return;
        }

        for (ObfuscatorChain chain : chains) {
            for (int i = 0; i < configuration.getObfDepth(); i++) {
                List<Stmt> newStmts = new ArrayList<>();
                List<Stmt> origStmts = new ArrayList<>();
                for (Stmt value : ir.stmts) {
                    origStmts.add(value);
                }
                RebuildIfResult rebuildIfResult;
                for (Stmt stmt : ir.stmts) {
                    if (chain.canHandle(ir, stmt)) {
                        rebuildIfResult = chain.reBuild(ir, stmt, origStmts);
                        if (rebuildIfResult != null) {
                            newStmts.addAll(rebuildIfResult.getResult());
                        }
                    } else {
                        newStmts.add(stmt);
                    }
                }
                chain.reBuildEnd(ir, newStmts, origStmts);
                ir.stmts.clear();
                ir.stmts.addAll(newStmts);
                if (!chain.canDepth()) {
                    break;
                }
            }
        }

        // 入口局部变量初始化：
        // 状态机（lookupswitch + 循环）重建后，某些局部变量只在循环内分支赋值、
        // 在循环收敛点读取（如 Abx.go 的返回值临时变量）。该模式是合法的（JVM 验证通过），
        // 但 R8 8.x 构建 SSA 时会抛 NegativeArraySizeException（实测 R8 8.7.18 稳定复现）。
        // 在方法入口给所有非参数局部变量赋默认值，消除"收敛帧读取未定义变量"，
        // 即可让 D8/R8 正常解析混淆产物。
        if (hasLookupSwitch(ir)) {
            insertLocalInitializers(ir);
        }

    }

    private static boolean hasLookupSwitch(IrMethod ir) {
        for (Stmt s : ir.stmts) {
            if (s instanceof LookupSwitchStmt) {
                return true;
            }
        }
        return false;
    }

    private static void insertLocalInitializers(IrMethod ir) {
        Set<Integer> params = new HashSet<>();
        for (Stmt s : ir.stmts) {
            if (s.st == Stmt.ST.IDENTITY && s instanceof AssignStmt) {
                Value left = ((AssignStmt) s).op1;
                if (left instanceof Local) {
                    params.add(((Local) left)._ls_index);
                }
            }
        }
        List<Stmt> init = new ArrayList<>();
        for (Local local : ir.locals) {
            if (params.contains(local._ls_index)) {
                continue;
            }
            String type = inferType(local, ir.stmts);
            if (type == null) {
                continue;
            }
            Constant zero = zeroFor(type);
            if (zero == null) {
                continue;
            }
            init.add(Stmts.nAssign(local, zero));
        }
        if (!init.isEmpty()) {
            List<Stmt> all = new ArrayList<>(init);
            for (Stmt s : ir.stmts) {
                all.add(s);
            }
            ir.stmts.clear();
            ir.stmts.addAll(all);
        }
    }

    private static String inferType(Local local, StmtList stmts) {
        for (Stmt s : stmts) {
            if (s.st == Stmt.ST.ASSIGN && s instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) s;
                Value left = as.op1;
                if (left instanceof Local && ((Local) left)._ls_index == local._ls_index) {
                    Value right = as.op2;
                    if (right.valueType != null && right.valueType.length() > 0 && !"V".equals(right.valueType)) {
                        return right.valueType;
                    }
                }
            }
        }
        return null;
    }

    private static Constant zeroFor(String type) {
        switch (type.charAt(0)) {
            case 'I':
            case 'S':
            case 'B':
            case 'Z':
            case 'C':
                return Exprs.nInt(0);
            case 'J':
                return Exprs.nLong(0);
            case 'F':
                return Exprs.nFloat(0);
            case 'D':
                return Exprs.nDouble(0);
            default:
                return Exprs.nNull();
        }
    }
}

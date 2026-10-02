package top.niunaijun.obfuscator.chain.base;

import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.Local;
import com.googlecode.dex2jar.ir.stmt.Stmt;
import top.niunaijun.obfuscator.ObfDic;
import top.niunaijun.obfuscator.ObfuscatorConfiguration;
import top.niunaijun.obfuscator.RebuildIfResult;

import java.util.List;
import java.util.Random;
import java.util.Set;

public abstract class BaseObfuscatorChain implements ObfuscatorChain {

    private int localIndex;
    private IrMethod irMethod;
    protected int depth;
    protected ObfuscatorConfiguration obfuscatorConfiguration;

    public BaseObfuscatorChain(ObfuscatorConfiguration obfuscatorConfiguration) {
        this.obfuscatorConfiguration = obfuscatorConfiguration;
        this.depth = obfuscatorConfiguration == null ? 1 : obfuscatorConfiguration.getObfDepth();
    }

    @Override
    public boolean canDepth() {
        return true;
    }

    public abstract RebuildIfResult reBuild0(IrMethod ir, Stmt stmt, List<Stmt> origStmts);

    @Override
    public final RebuildIfResult reBuild(IrMethod ir, Stmt stmt, List<Stmt> origStmts) {
        localIndex = ir.locals.size() * 2;
        irMethod = ir;
        return reBuild0(ir, stmt, origStmts);
    }

    public Local newLocal(String name, String type) {
        Local local = Exprs.nLocal(++localIndex, name);
        local.valueType = type;
        irMethod.locals.add(local);
        return local;
    }

    /**
     * 生成一个当前方法内唯一的、非 0 的 switch key（0 被 LBlock.NO_KEY 保留）。
     * 用于 int 状态机调度，避免旧的 String.hashCode() 调度在 D8/R8 下触发
     * NegativeArraySizeException。
     */
    public int randomKey(Set<Integer> usedKeys) {
        int key;
        do {
            key = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;
        } while (usedKeys.contains(key));
        usedKeys.add(key);
        return key;
    }

//    int index = 0;
//    public String randomString(int depth) {
//        return (index++) + "";
//    }

    public String randomString(int depth) {
        // 原版用法：用 ObfDic 词典词拼接局部变量名，
        // 使状态机/switch 相关变量在 smali 层显示为阿拉伯乱码（如 ۫۫۬ۨۚۢ）。
        // 注意变量名只存在于 dex debug_info，对 jadx 类反编译器影响有限，
        // 但对 baksmali/smali 层面是直接的干扰。
        int length = (new Random().nextInt(5 ) + 5) * depth;
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            stringBuilder.append(ObfDic.dic[new Random().nextInt(ObfDic.dic.length - 1)]);
        }
        return stringBuilder.toString();
    }
}

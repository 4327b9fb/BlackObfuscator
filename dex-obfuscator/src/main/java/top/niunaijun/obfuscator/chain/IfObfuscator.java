package top.niunaijun.obfuscator.chain;

import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.Trap;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.Local;
import com.googlecode.dex2jar.ir.stmt.*;
import top.niunaijun.obfuscator.LBlock;
import top.niunaijun.obfuscator.ObfuscatorConfiguration;
import top.niunaijun.obfuscator.RebuildIfResult;
import top.niunaijun.obfuscator.chain.base.BaseObfuscatorChain;

import java.util.*;

import static com.googlecode.dex2jar.ir.stmt.Stmt.ST.IF;
import static com.googlecode.dex2jar.ir.stmt.Stmt.ST.LABEL;

/**
 * 条件跳转（if）混淆：把单个 if 改写为 int 状态机调度。
 *
 * 相对原版的关键修复：原实现用 String key + hashCode() 生成 lookupswitch 键，
 * 与 D8/R8 的 NegativeArraySizeException 缺陷直接相关；现改为纯 int 状态机，
 * 语义与混淆强度不变。
 */
public class IfObfuscator extends BaseObfuscatorChain {

    public IfObfuscator(ObfuscatorConfiguration obfuscatorConfiguration) {
        super(obfuscatorConfiguration);
    }

    @Override
    public boolean canHandle(IrMethod ir, Stmt stmt) {
        return stmt.st == IF;
    }

    @Override
    public RebuildIfResult reBuild0(IrMethod ir, Stmt stmt, List<Stmt> origStmts) {
        List<Stmt> newStmts = new ArrayList<>();
        IfStmt ifStmt = (IfStmt) stmt;
        // if goto
        LBlock targetBlock = getIfTargetStmts(ifStmt);

        LBlock elseBlock = generateLBlock();

        Set<Integer> usedKeys = new HashSet<>();
        int keyGoto = randomKey(usedKeys);
        int keyElse = randomKey(usedKeys);
        int keyEnter = randomKey(usedKeys);
        int keyNext = randomKey(usedKeys);
        int keyFake = randomKey(usedKeys);

        Local obfState = newLocal("obf_state", "I");

        LBlock startBlock = generateLBlock();
        newStmts.add(startBlock.getLabelStmt());

        // 状态机初始状态 = ENTER
        newStmts.add(Stmts.nAssign(obfState, Exprs.nInt(keyEnter)));

        LabelStmt whileBegin = createWhile(newStmts);

        LBlock fake = generateLBlock();
        fake.getStmts().add(Stmts.nAssign(obfState, obfState));
        fake.getStmts().add(Stmts.nGoto(whileBegin));

        // goto的跳板，由于没办法直接回到whileBegin并且计算obfState，所以需要此跳板来来操作.
        // 跳板将跳转回原goto
        LBlock gotoJumpBlock = generateLBlock();
        gotoJumpBlock.getStmts().add(Stmts.nAssign(obfState, Exprs.nInt(keyGoto)));
        gotoJumpBlock.getStmts().add(Stmts.nGoto(whileBegin));

        LBlock enterBlock = generateLBlock();
        ifStmt.target = gotoJumpBlock.getLabelStmt();
        enterBlock.getStmts().add(ifStmt);
        enterBlock.getStmts().add(Stmts.nAssign(obfState, Exprs.nInt(keyElse)));
        enterBlock.getStmts().add(Stmts.nGoto(whileBegin));

        // else块需要重跳回whileBegin，进行最后跳跃到nextStep
        elseBlock.getStmts().add(Stmts.nAssign(obfState, Exprs.nInt(keyNext)));
        elseBlock.getStmts().add(Stmts.nGoto(whileBegin));

        LBlock nextBlock = generateLBlock();

        LBlock defaultTarget = generateLBlock();

        Map<Integer, LabelStmt> switchBlock = new LinkedHashMap<>();
        switchBlock.put(keyGoto, targetBlock.getLabelStmt());
        switchBlock.put(keyElse, elseBlock.getLabelStmt());
        switchBlock.put(keyEnter, enterBlock.getLabelStmt());
        switchBlock.put(keyNext, nextBlock.getLabelStmt());
        switchBlock.put(keyFake, fake.getLabelStmt());
        List<Integer> sortList = new ArrayList<>(switchBlock.keySet());
        Collections.shuffle(sortList);
        Map<Integer, LabelStmt> newSwitchBlock = new LinkedHashMap<>();
        for (Integer integer : sortList) {
            newSwitchBlock.put(integer, switchBlock.get(integer));
        }

        // switch(obfState)
        // lookupswitch 的 match 值必须升序（JVMS 6.5），否则类文件非法（JVM 验证器
        // "Bad lookupswitch instruction"，D8/R8 解析时报 NegativeArraySizeException）。
        List<Integer> sortedSwitchKeys = new ArrayList<>(newSwitchBlock.keySet());
        Collections.sort(sortedSwitchKeys);
        int[] switchKeys = new int[sortedSwitchKeys.size()];
        LabelStmt[] switchTargets = new LabelStmt[sortedSwitchKeys.size()];
        for (int i = 0; i < sortedSwitchKeys.size(); i++) {
            switchKeys[i] = sortedSwitchKeys.get(i);
            switchTargets[i] = newSwitchBlock.get(sortedSwitchKeys.get(i));
        }
        LookupSwitchStmt lookupSwitchStmt = Stmts.nLookupSwitch(obfState,
                switchKeys, switchTargets,
                defaultTarget.getLabelStmt());

        newStmts.add(lookupSwitchStmt);
        // add default, do noting
        newStmts.add(defaultTarget.getLabelStmt());
        newStmts.addAll(defaultTarget.getStmts());
        // add fake
        newStmts.add(fake.getLabelStmt());
        newStmts.addAll(fake.getStmts());
        // add goto jump
        newStmts.add(gotoJumpBlock.getLabelStmt());
        newStmts.addAll(gotoJumpBlock.getStmts());
        // add enter
        newStmts.add(enterBlock.getLabelStmt());
        newStmts.addAll(enterBlock.getStmts());
        // add else
        newStmts.add(elseBlock.getLabelStmt());
        newStmts.addAll(elseBlock.getStmts());
        // add next
        newStmts.add(nextBlock.getLabelStmt());
        newStmts.addAll(nextBlock.getStmts());

        LabelStmt ifStmtLabel = findIfStmtLabel(ifStmt, origStmts);
        if (ifStmtLabel != null) {
            for (Trap trap : ir.traps) {
                if (trap.end == ifStmtLabel) {
                    trap.end = elseBlock.getLabelStmt();
                }
            }
        }
        return new RebuildIfResult(newStmts);
    }

    @Override
    public void reBuildEnd(IrMethod ir, List<Stmt> newStmts, List<Stmt> origStmts) {

    }


    private LBlock generateLBlock() {
        return new LBlock();
    }

    private LabelStmt createWhile(List<Stmt> newStmts) {
        LBlock block = new LBlock();
        newStmts.add(block.getLabelStmt());
        return block.getLabelStmt();
    }

    private LBlock getIfTargetStmts(IfStmt ifStmt) {
        LBlock block = new LBlock();
        block.setLabelStmt(ifStmt.target);
        return block;
    }

    private LabelStmt findIfStmtLabel(IfStmt ifStmt, List<Stmt> orig) {
        boolean found = false;
        for (int i = orig.size() - 1; i >= 0; i--) {
            Stmt stmt = orig.get(i);
            if (stmt == ifStmt) {
                found = true;
            } else if (found && stmt.st == LABEL) {
                return (LabelStmt) stmt;
            }
        }
        return null;
    }


}

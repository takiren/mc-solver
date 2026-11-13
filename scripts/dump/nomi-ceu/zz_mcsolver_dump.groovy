// mc-solver 用のレシピダンプ (Nomi-CEu / MC 1.12.2 / GroovyScript + GregTech CEu)
//
// 置き場所: <インスタンス>/groovy/post/addon/zz_mcsolver_dump.groovy
//   Nomi-CEu の runConfig.json は postInit で post/classes → post/main → post/addon の順に
//   実行する。post/addon は最後なので、パック本体のレシピ変更がすべて終わった後に走る。
// 出力先:   <インスタンス>/logs/groovy_server.log (クライアントで動かした場合は groovy.log)
//
// GroovyScript はファイル書き込みを禁止しているので、1 レコードを 1 行の
// "MCSOLVER|<JSON を Base64 にしたもの>" としてログに書く。
// scripts/dump/extract-log.ts でログから JSONL に戻す。
//
// ダンプが終わったらこのファイルは削除する (起動のたびに実行されるため)。

import com.google.gson.Gson
import gregtech.api.recipes.RecipeMap
import net.minecraft.item.ItemStack
import net.minecraft.item.crafting.FurnaceRecipes
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fml.common.registry.ForgeRegistries
import net.minecraftforge.oredict.OreDictionary

def gson = new Gson()
def counts = [:]
def names = [:]
def fluidNames = [:]
def errorCount = 0
def errorCounts = [:]
def WILDCARD = 32767

def emit = { Map rec ->
    def json = gson.toJson(rec)
    log.info('MCSOLVER|' + Base64.getEncoder().encodeToString(json.getBytes('UTF-8')))
    counts[rec.t] = (counts[rec.t] ?: 0) + 1
}

// kind ごとに件数を数え、先頭 5 件だけ内容をログに出す
def fail = { String kind, String detail, Throwable e ->
    errorCount++
    errorCounts[kind] = (errorCounts[kind] ?: 0) + 1
    if (errorCounts[kind] <= 5) {
        // e のメソッドを Groovy から直接呼ばない。例外がサンドボックスの禁止クラス
        // (SandboxSecurityException など) だと、呼び出し自体が禁止されてスクリプト全体が止まる。
        def msg = '?'
        try {
            msg = String.valueOf((Object) e)
        } catch (Throwable ignored) {
        }
        log.info('MCSOLVER_ERR ' + kind + ' ' + detail + ': ' + msg)
    }
}

def stackOf = { ItemStack s ->
    if (s == null || s.isEmpty()) return null
    // Item のメソッドを直接呼ばない。Groovy が Item のサブクラスをリフレクションで調べるとき、
    // クライアント専用クラス (ITooltipFlag など) を引数に持つメソッドがあるとサーバーで落ちる。
    def id = ForgeRegistries.ITEMS.getKey(s.getItem()).toString()
    def meta = s.getMetadata()
    def m = [id: id, meta: meta, count: s.getCount()]
    if (s.hasTagCompound()) m.nbt = s.getTagCompound().toString()
    def key = id + ':' + meta
    if (meta != WILDCARD && !names.containsKey(key)) {
        try {
            names[key] = s.getDisplayName()
        } catch (Throwable e) {
            names[key] = null
        }
    }
    return m
}

def fluidOf = { FluidStack f ->
    if (f == null || f.getFluid() == null) return null
    def name = f.getFluid().getName()
    if (!fluidNames.containsKey(name)) {
        try {
            fluidNames[name] = f.getLocalizedName()
        } catch (Throwable e) {
            fluidNames[name] = null
        }
    }
    return [fluid: name, amount: f.amount]
}

def stacksOf = { list ->
    def out = []
    for (s in list) {
        def m = stackOf(s)
        if (m != null) out.add(m)
    }
    return out
}

emit([t: 'begin', format: 1])

// ---- 鉱石辞書 ----
for (name in OreDictionary.getOreNames()) {
    try {
        def items = stacksOf(OreDictionary.getOres(name))
        if (!items.isEmpty()) emit([t: 'ore', name: name, items: items])
    } catch (Throwable e) {
        fail('ore', name, e)
    }
}

// ---- GregTech の機械レシピ ----
for (map in RecipeMap.getRecipeMaps()) {
    def machine = map.getUnlocalizedName()
    for (r in map.getRecipeList()) {
        try {
            def rec = [t: 'gt', machine: machine, duration: r.getDuration(), eut: r.getEUt()]
            if (r.isHidden()) rec.hidden = true

            def inputs = []
            for (inp in r.getInputs()) {
                def m = [amount: inp.getAmount()]
                if (inp.isNonConsumable()) m.nc = true
                if (inp.isOreDict()) {
                    m.ore = OreDictionary.getOreName(inp.getOreDict())
                } else {
                    def stacks = inp.getInputStacks()
                    m.items = stacks == null ? [] : stacksOf(stacks.toList())
                }
                inputs.add(m)
            }
            rec.inputs = inputs

            def fluidInputs = []
            for (inp in r.getFluidInputs()) {
                def m = fluidOf(inp.getInputFluidStack())
                if (m == null) continue
                m.amount = inp.getAmount()
                if (inp.isNonConsumable()) m.nc = true
                fluidInputs.add(m)
            }
            if (!fluidInputs.isEmpty()) rec.fluidInputs = fluidInputs

            rec.outputs = stacksOf(r.getOutputs())

            def chanced = []
            for (c in r.getChancedOutputs().getChancedEntries()) {
                def m = stackOf(c.getIngredient())
                if (m == null) continue
                m.chance = c.getChance()
                m.boost = c.getChanceBoost()
                chanced.add(m)
            }
            if (!chanced.isEmpty()) rec.chancedOutputs = chanced

            def fluidOutputs = []
            for (f in r.getFluidOutputs()) {
                def m = fluidOf(f)
                if (m != null) fluidOutputs.add(m)
            }
            if (!fluidOutputs.isEmpty()) rec.fluidOutputs = fluidOutputs

            def chancedFluids = []
            for (c in r.getChancedFluidOutputs().getChancedEntries()) {
                def m = fluidOf(c.getIngredient())
                if (m == null) continue
                m.chance = c.getChance()
                m.boost = c.getChanceBoost()
                chancedFluids.add(m)
            }
            if (!chancedFluids.isEmpty()) rec.chancedFluidOutputs = chancedFluids

            emit(rec)
        } catch (Throwable e) {
            fail('gt', machine, e)
        }
    }
}

// ---- クラフトレシピ ----
for (r in ForgeRegistries.RECIPES.getValuesCollection()) {
    def craftName = '?'
    try {
        craftName = String.valueOf((Object) ForgeRegistries.RECIPES.getKey(r))
        def out = stackOf(r.getRecipeOutput())
        if (out == null) continue
        def slots = []
        for (ing in r.getIngredients()) {
            def alts = stacksOf(ing.getMatchingStacks().toList())
            if (!alts.isEmpty()) slots.add(alts)
        }
        if (slots.isEmpty()) continue
        emit([t: 'craft', name: craftName, type: r.getClass().getName(), output: out, slots: slots])
    } catch (Throwable e) {
        fail('craft', craftName, e)
    }
}

// ---- かまど ----
for (e in FurnaceRecipes.instance().getSmeltingList().entrySet()) {
    try {
        def input = stackOf(e.getKey())
        def out = stackOf(e.getValue())
        if (input != null && out != null) emit([t: 'smelt', input: input, output: out])
    } catch (Throwable ex) {
        fail('smelt', '', ex)
    }
}

// ---- 表示名 ----
for (e in names.entrySet()) {
    if (e.getValue() != null) emit([t: 'item', key: e.getKey(), name: e.getValue()])
}
for (e in fluidNames.entrySet()) {
    if (e.getValue() != null) emit([t: 'fluid', key: e.getKey(), name: e.getValue()])
}

def summary = [t: 'end', errors: errorCount, errorCounts: errorCounts, counts: counts.clone()]
emit(summary)
log.info('MCSOLVER_DONE errors=' + errorCount + ' ' + errorCounts.toString() + ' counts=' + counts.toString())

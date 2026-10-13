package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Kind

/**
 * The seam between Magic Hand and a shared element → action mapping (v0.24.7).
 *
 * The browser pointer (v0.24.6, branch browser-assistant-ui) brings its own radial menu and element → action mapping.
 * Magic Hand asks [MagicHandMenu.mapper] for its actions and draws them with the radial renderer in
 * ui/magichand/RadialMenu.kt, so switching both to the shared implementation is one assignment each: point [mapper]
 * at an adapter over the browser mapping and `RadialMenus.renderer` at its menu, and keep the Magic Hand-only actions
 * (Carry, Watch this, Drop here) appended by [withHandActions].
 */
interface ElementActionMapper {
    /** Actions for an element of [kind]; [carrying] = the hand holds something that could be dropped. */
    fun actionsFor(kind: Kind, carrying: Boolean): List<Action>
}

/** Magic Hand's own mapping (MagicHandLogic.actionsFor). */
object DefaultHandMapper : ElementActionMapper {
    override fun actionsFor(kind: Kind, carrying: Boolean): List<Action> = MagicHandLogic.actionsFor(kind, carrying)
}

object MagicHandMenu {
    @Volatile var mapper: ElementActionMapper = DefaultHandMapper

    /** The menu for [kind], from the current mapper, with the hand-only actions kept and the result capped. */
    fun actions(kind: Kind, carrying: Boolean): List<Action> = withHandActions(mapper.actionsFor(kind, carrying), kind, carrying)

    /** Makes sure Carry / Watch / Drop survive a mapper that doesn't know them (e.g. the browser's). */
    fun withHandActions(base: List<Action>, kind: Kind, carrying: Boolean): List<Action> {
        if (kind == Kind.SECRET) return emptyList()
        val out = base.toMutableList()
        if (carrying && kind == Kind.FIELD && Action.DROP !in out) out.add(0, Action.DROP)
        if (kind == Kind.VALUE && Action.WATCH !in out) out.add(minOf(1, out.size), Action.WATCH)
        if (kind != Kind.EMPTY && Action.CARRY !in out && !(carrying && kind == Kind.FIELD)) out.add(Action.CARRY)
        if (carrying && kind != Kind.FIELD && Action.DROP_IN_CHAT !in out) out.add(Action.DROP_IN_CHAT)
        val ask = out.indexOf(Action.ASK)
        if (ask > 0 && !(carrying && kind == Kind.FIELD)) { out.removeAt(ask); out.add(0, Action.ASK) }
        return out.distinct().let { l ->
            if (l.size <= MagicHandLogic.MAX_ACTIONS) l
            else {
                // Keep the hand-only actions; trim generic ones from the end.
                val keep = setOf(Action.ASK, Action.DROP, Action.WATCH, Action.CARRY, Action.DROP_IN_CHAT)
                val extra = l.size - MagicHandLogic.MAX_ACTIONS
                val drop = l.reversed().filter { it !in keep }.take(extra).toSet()
                l.filter { it !in drop }.take(MagicHandLogic.MAX_ACTIONS)
            }
        }
    }
}

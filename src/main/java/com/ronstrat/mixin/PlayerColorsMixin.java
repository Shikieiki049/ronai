package com.ronstrat.mixin;

import com.solegendary.reignofnether.player.PlayerClientEvents;
import com.solegendary.reignofnether.player.PlayerColors;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;

/**
 * A RON display fix, the same for every player: start positions from a map's rtsmap.json (Burnt-Out Paradise,
 * New York City...) get their team colour as the colour's hex code (StartPosServerEvents.loadPositionsFromMapInfo:
 * PlayerColors.colors[i].hexCode), but the client looks a player's colour up by the block's map colour id
 * (mappedColors). The hex is not found there, so every player fell back to a colour of their own by index -
 * a team of the same colour showed in two (the user's 3v3 / 2v2 games). Here a colour id that is a known hex
 * code is taken as that colour.
 */
@Mixin(value = PlayerColors.class, remap = false)
public abstract class PlayerColorsMixin {
    @Shadow @Final private static HashMap<Integer, PlayerColors.PlayerColor> mappedColors;
    @Shadow private static HashMap<String, Integer> playerColorId;

    @Inject(method = "getPlayerColorId", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ronstrat$hexTeamColour(String playerName, CallbackInfoReturnable<Integer> cir) {
        if (playerName == null || playerName.isBlank()) return;
        int mc = PlayerClientEvents.getPlayerMapColorId(playerName);
        if (mc <= 0 || mappedColors.containsKey(mc)) return;
        for (PlayerColors.PlayerColor c : PlayerColors.colors) {
            if (c != null && c.hexCode == mc) {
                playerColorId.put(playerName, c.id);
                cir.setReturnValue(c.id);
                return;
            }
        }
    }
}

package com.palmergames.bukkit.towny.utils;

import java.util.List;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import com.earth2me.essentials.Teleport;
import com.earth2me.essentials.User;
import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyTimerHandler;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.exceptions.EconomyException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.NationSpawnLevel;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.SpawnType;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownSpawnLevel;
import com.palmergames.bukkit.towny.object.EconomyAccount;
import com.palmergames.bukkit.towny.object.TownyObject;
import com.palmergames.bukkit.towny.permissions.PermissionNodes;
import com.palmergames.bukkit.towny.tasks.CooldownTimerTask;
import com.palmergames.bukkit.towny.tasks.CooldownTimerTask.CooldownType;

public class SpawnUtil {

	private static Towny plugin;

	public static void initialize(Towny plugin) {
		SpawnUtil.plugin = plugin;
	}

	/**
	 * Central Util for /res, /t, /n, /ta spawn commands.
	 * 
	 * @param player       - Player using spawn command.
	 * @param split        - Remaining command arguments, used primarily for
	 *                     outposts.
	 * @param townyObject  - Either a town or nation depending on source command.
	 * @param notAffordMSG - Message shown when a player cannot afford their
	 *                     teleport.
	 * @param outpost      - Whether this is an outpost or not.
	 * @param spawnType    - SpawnType.RESIDENT/TOWN/NATION
	 * @throws TownyException - Thrown if any of the vital conditions are not met.
	 */
	public static void sendToTownySpawn(Player player, String[] split, TownyObject townyObject, String notAffordMSG, boolean outpost, SpawnType spawnType) throws TownyException {
		TownyUniverse townyUniverse = TownyUniverse.getInstance();

		Resident resident = townyUniverse.getDataSource().getResident(player.getName());
		// Test if the resident is in a teleport cooldown.
		if (TownySettings.getSpawnCooldownTime() > 0
				&& CooldownTimerTask.hasCooldown(resident.getName(), CooldownType.TELEPORT))
			throw new TownyException(
					String.format(TownySettings.getLangString("msg_err_cannot_spawn_x_seconds_remaining"),
							CooldownTimerTask.getCooldownRemaining(resident.getName(), CooldownType.TELEPORT)));

		// Disallow jailed players from teleporting.
		if (resident.isJailed())
			throw new TownyException(TownySettings.getLangString("msg_cannot_spawn_while_jailed"));

		Town town = null;
		Nation nation = null;
		Location spawnLoc = null;
		TownSpawnLevel townSpawnPermission = null;
		NationSpawnLevel nationSpawnPermission = null;
		boolean isTownyAdmin = townyUniverse.getPermissionSource().has(player, spawnType.getNode());

		// Figure out which Town/NationSpawnLevel this is.
		// Resolve where the spawnLoc will be.
		switch (spawnType) {
		case RESIDENT:
			if (resident.hasTown())
				town = resident.getTown();
			if (TownySettings.getBedUse() && player.getBedSpawnLocation() != null)
				spawnLoc = player.getBedSpawnLocation();
			else if (town != null)
				spawnLoc = town.getSpawn();
			else
				spawnLoc = plugin.getCache(player).getLastLocation().getWorld().getSpawnLocation();

			if (isTownyAdmin) {
				townSpawnPermission = TownSpawnLevel.ADMIN;
			} else {
				townSpawnPermission = TownSpawnLevel.TOWN_RESIDENT;
			}
			break;

		case TOWN:
			town = (Town) townyObject;
			if (outpost) {
				if (!town.hasOutpostSpawn())
					throw new TownyException(TownySettings.getLangString("msg_err_outpost_spawn"));

				Integer index = null;
				try {
					if (!split[split.length - 1].contains("name:")) {
						index = Integer.parseInt(split[split.length - 1]);
					} else { // So now it say's name:123
						split[split.length - 1] = split[split.length - 1].replace("name:", "").replace("_", " ");
						for (Location loc : town.getAllOutpostSpawns()) {
							TownBlock tboutpost = TownyAPI.getInstance().getTownBlock(loc);
							if (tboutpost != null) {
								String name = tboutpost.getName();
								if (name.startsWith(split[split.length - 1])) {
									index = 1 + town.getAllOutpostSpawns().indexOf(loc);
								}
							}
						}
						if (index == null) { // If it persists to be null, so it's not been given a value, set it to the
												// fallback (1).
							index = 1;
						}
					}
				} catch (NumberFormatException e) {
					// invalid entry so assume the first outpost, also note: We DO NOT HAVE a number
					// now, which means: if you type abc, you get brought to that outpost.
					// Let's consider the fact however: an outpost name begins with "123" and there
					// are 123 Outposts. Then we put the prefix name:123 and that solves that.
					index = 1;
					// Trying to get Outpost names.
					split[split.length - 1] = split[split.length - 1].replace("_", " ");
					for (Location loc : town.getAllOutpostSpawns()) {
						TownBlock tboutpost = TownyAPI.getInstance().getTownBlock(loc);
						if (tboutpost != null) {
							String name = tboutpost.getName();
							if (name.startsWith(split[split.length - 1])) {
								index = 1 + town.getAllOutpostSpawns().indexOf(loc);
							}
						}
					}
				} catch (ArrayIndexOutOfBoundsException i) {
					// Number not present so assume the first outpost.
					index = 1;
				}

				if (TownySettings.isOutpostLimitStoppingTeleports() && TownySettings.isOutpostsLimitedByLevels()
						&& town.isOverOutpostLimit() && (Math.max(1, index) > town.getOutpostLimit())) {
					throw new TownyException(String.format(TownySettings.getLangString("msg_err_over_outposts_limit"),
							town.getMaxOutpostSpawn(), town.getOutpostLimit()));
				}

				spawnLoc = town.getOutpostSpawn(Math.max(1, index));
			} else
				spawnLoc = town.getSpawn();

			// Determine conditions
			if (isTownyAdmin) {
				townSpawnPermission = TownSpawnLevel.ADMIN;
			} else if ((split.length == 0) && (!outpost)) {
				townSpawnPermission = TownSpawnLevel.TOWN_RESIDENT;
			} else {
				// split.length > 1
				if (!resident.hasTown()) {
					townSpawnPermission = TownSpawnLevel.UNAFFILIATED;
				} else if (resident.getTown() == town) {
					townSpawnPermission = outpost ? TownSpawnLevel.TOWN_RESIDENT_OUTPOST : TownSpawnLevel.TOWN_RESIDENT;
				} else if (resident.hasNation() && town.hasNation()) {
					Nation playerNation = resident.getTown().getNation();
					Nation targetNation = town.getNation();

					if (playerNation == targetNation) {
						if (!town.isPublic() && TownySettings.isAllySpawningRequiringPublicStatus())
							throw new TownyException(
									String.format(TownySettings.getLangString("msg_err_ally_isnt_public"), town));
						else
							townSpawnPermission = TownSpawnLevel.PART_OF_NATION;
					} else if (targetNation.hasEnemy(playerNation)) {
						// Prevent enemies from using spawn travel.
						throw new TownyException(TownySettings.getLangString("msg_err_public_spawn_enemy"));
					} else if (targetNation.hasAlly(playerNation)) {
						if (!town.isPublic() && TownySettings.isAllySpawningRequiringPublicStatus())
							throw new TownyException(
									String.format(TownySettings.getLangString("msg_err_ally_isnt_public"), town));
						else
							townSpawnPermission = TownSpawnLevel.NATION_ALLY;
					} else {
						townSpawnPermission = TownSpawnLevel.UNAFFILIATED;
					}
				} else {
					townSpawnPermission = TownSpawnLevel.UNAFFILIATED;
				}
			}

			TownyMessaging.sendDebugMsg(townSpawnPermission.toString() + " " + townSpawnPermission.isAllowed(town));
			townSpawnPermission.checkIfAllowed(plugin, player, town);

			// Check the permissions
			if (!(isTownyAdmin || ((townSpawnPermission == TownSpawnLevel.UNAFFILIATED) ? town.isPublic()
					: townSpawnPermission.hasPermissionNode(plugin, player, town))))
				throw new TownyException(TownySettings.getLangString("msg_err_not_public"));

			// Prevent outlaws from spawning into towns they're considered an outlaw in.
			if (!isTownyAdmin && town.hasOutlaw(resident))
					throw new TownyException(String.format(TownySettings.getLangString("msg_error_cannot_town_spawn_youre_an_outlaw_in_town"), town));

			break;
		case NATION:
			nation = (Nation) townyObject;
			spawnLoc = nation.getNationSpawn();

			// Determine conditions
			if (isTownyAdmin) {
				nationSpawnPermission = NationSpawnLevel.ADMIN;
			} else if (split.length == 0) {
				nationSpawnPermission = NationSpawnLevel.PART_OF_NATION;
			} else {
				// split.length > 1
				if (!resident.hasTown()) {
					nationSpawnPermission = NationSpawnLevel.UNAFFILIATED;
				} else if (resident.hasNation()) {
					Nation playerNation = resident.getTown().getNation();

					if (playerNation == nation) {
						nationSpawnPermission = NationSpawnLevel.PART_OF_NATION;
					} else if (nation.hasEnemy(playerNation)) {
						// Prevent enemies from using spawn travel.
						throw new TownyException(TownySettings.getLangString("msg_err_public_spawn_enemy"));
					} else if (nation.hasAlly(playerNation)) {
						nationSpawnPermission = NationSpawnLevel.NATION_ALLY;
					} else {
						nationSpawnPermission = NationSpawnLevel.UNAFFILIATED;
					}
				} else {
					nationSpawnPermission = NationSpawnLevel.UNAFFILIATED;
				}
			}

			// Check the permissions
			if (!(isTownyAdmin || ((nationSpawnPermission == NationSpawnLevel.UNAFFILIATED) ? nation.isPublic()
					: nationSpawnPermission.hasPermissionNode(plugin, player, nation))))
				throw new TownyException(TownySettings.getLangString("msg_err_nation_not_public"));

			break;
		}

		// Prevent spawn travel while in disallowed zones (if configured.)
		if (!isTownyAdmin) {
			List<String> disallowedZones = TownySettings.getDisallowedTownSpawnZones();

			if (!disallowedZones.isEmpty()) {
				String inTown;
				try {
					Location loc = plugin.getCache(player).getLastLocation();
					inTown = TownyAPI.getInstance().getTownName(loc);
				} catch (NullPointerException e) {
					inTown = TownyAPI.getInstance().getTownName(player.getLocation());
				}

				if (inTown == null && disallowedZones.contains("unclaimed"))
					throw new TownyException(
							String.format(TownySettings.getLangString("msg_err_x_spawn_disallowed_from_x"),
									spawnType.getTypeName(), TownySettings.getLangString("msg_the_wilderness")));
				if (inTown != null && resident.hasNation()
						&& townyUniverse.getDataSource().getTown(inTown).hasNation()) {
					Nation inNation = townyUniverse.getDataSource().getTown(inTown).getNation();
					Nation playerNation = resident.getTown().getNation();
					if (inNation.hasEnemy(playerNation) && disallowedZones.contains("enemy"))
						throw new TownyException(
								String.format(TownySettings.getLangString("msg_err_x_spawn_disallowed_from_x"),
										spawnType.getTypeName(), TownySettings.getLangString("msg_enemy_areas")));
					if (!inNation.hasAlly(playerNation) && !inNation.hasEnemy(playerNation)
							&& disallowedZones.contains("neutral"))
						throw new TownyException(
								String.format(TownySettings.getLangString("msg_err_x_spawn_disallowed_from_x"),
										spawnType.getTypeName(), TownySettings.getLangString("msg_neutral_towns")));
				}
			}
		}

		double travelCost = 0.0;
		String spawnPermission = null;
		EconomyAccount payee = null;
		// Figure out costs, payee and spawnPermmission slug for money.csv log.
		switch (spawnType) {
		case RESIDENT:
		case TOWN:
			// Taking whichever is smaller, the cost of the spawn price set by the town, or
			// the cost set in the config (which is the maximum a town can set their
			// spawncost to.)
			travelCost = Math.min(townSpawnPermission.getCost(town), townSpawnPermission.getCost());
			spawnPermission = String.format(spawnType.getTypeName() + " (%s)", townSpawnPermission);
			payee = town.getAccount();
			break;
		case NATION:
			// Taking whichever is smaller, the cost of the spawn price set by the nation,
			// or the cost set in the config (which is the maximum a nation can set their
			// spawncost to.)
			travelCost = Math.min(nationSpawnPermission.getCost(nation), nationSpawnPermission.getCost());
			spawnPermission = String.format(spawnType.getTypeName() + " (%s)", nationSpawnPermission);
			payee = nation.getAccount();
			break;
		}

		// Check if need/can pay.
		try {
			if ((!townyUniverse.getPermissionSource().has(player,
					PermissionNodes.TOWNY_COMMAND_TOWNYADMIN_TOWN_SPAWN_FREECHARGE.getNode()))
					&& (travelCost > 0 && TownySettings.isUsingEconomy()
							&& (resident.getAccount().getHoldingBalance() < travelCost)))
				throw new TownyException(notAffordMSG);
		} catch (EconomyException ignored) {
		}

		// Essentials tests.
		boolean usingESS = plugin.isEssentials();

		if (usingESS && !isTownyAdmin) {
			try {
				User essentialsUser = plugin.getEssentials().getUser(player);

				// This jail check is specifically for essentials jails, not towny ones.
				if (!essentialsUser.isJailed()) {

					Teleport teleport = essentialsUser.getTeleport();
					// Cause an essentials exception if in cooldown.
					teleport.cooldown(true);
					teleport.teleport(spawnLoc, null, TeleportCause.COMMAND);
				}
			} catch (Exception e) {
				TownyMessaging.sendErrorMsg(player, "Error: " + e.getMessage());
				return;
			}
		}

		// Actual taking of monies here.
		if (!townyUniverse.getPermissionSource().has(player,
				PermissionNodes.TOWNY_COMMAND_TOWNYADMIN_TOWN_SPAWN_FREECHARGE.getNode())) {
			if (!TownySettings.isTownSpawnPaidToTown())
				payee = EconomyAccount.SERVER_ACCOUNT;
			// Show message if we are using an Economy and are charging for spawn travel.
			try {
				if (travelCost > 0 && TownySettings.isUsingEconomy()
						&& resident.getAccount().payTo(travelCost, payee, spawnPermission)) {
					TownyMessaging.sendMsg(player, String.format(TownySettings.getLangString("msg_cost_spawn"),
							TownyEconomyHandler.getFormattedBalance(travelCost)));
				}
			} catch (EconomyException ignored) {
			}
		}
		
		// Used later to make sure the chunk we teleport to is loaded.
		Chunk chunk = spawnLoc.getChunk();

		// If an Admin or Essentials teleport isn't being used, use our own.
		if (isTownyAdmin) {
			if (player.getVehicle() != null)
				player.getVehicle().eject();
			if (!chunk.isLoaded())
				chunk.load();
			player.teleport(spawnLoc, TeleportCause.COMMAND);
			return;
		}

		if (!usingESS) {
			if (TownyTimerHandler.isTeleportWarmupRunning()) {
				// Use teleport warmup
				player.sendMessage(String.format(TownySettings.getLangString("msg_town_spawn_warmup"),
						TownySettings.getTeleportWarmupTime()));
				TownyAPI.getInstance().requestTeleport(player, spawnLoc);
			} else {
				// Don't use teleport warmup
				if (player.getVehicle() != null)
					player.getVehicle().eject();
				if (!chunk.isLoaded())
					chunk.load();
				player.teleport(spawnLoc, TeleportCause.COMMAND);
				if (TownySettings.getSpawnCooldownTime() > 0)
					CooldownTimerTask.addCooldownTimer(resident.getName(), CooldownType.TELEPORT);
			}
		}
	}

}

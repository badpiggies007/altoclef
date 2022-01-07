package adris.altoclef.tasks.misc.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.screen.slot.SlotActionType;
import adris.altoclef.util.baritone.GoalAnd;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.api.utils.input.Input;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalGetToBlock;
import net.minecraft.block.Blocks;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.phase.Phase;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import adris.altoclef.util.MiningRequirement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.block.Block;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.Items;

import java.util.HashMap;
public class KillEnderDragonWithBedsTask extends Task {

    private static final double DRAGON_HEAD_CLOSE_TO_BED_RANGE = 6.1;

    private final HashMap<BlockPos, Double> _breathCostMap = new HashMap<>();
    private final Task _whenNotPerchingTask;
    private final Task _collectBuildMaterialsTask = new MineAndCollectTask(new ItemTarget(Items.END_STONE, 100), new Block[]{Blocks.END_STONE}, MiningRequirement.WOOD);
    private BlockPos _endPortalTop;
    private Task _positionTask;
	private boolean kamakazed = false;
    private boolean haveEquipedWaterBucket = false;

    public KillEnderDragonWithBedsTask(IDragonWaiter notPerchingOverride) {
        _whenNotPerchingTask = (Task)notPerchingOverride;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL);
    }

    @Override
    protected Task onTick(AltoClef mod) {
	    updateBreathCostMap(mod);
        /*
        If dragon is perching:
            If we're not in position (XZ):
                Get in position (XZ)
            If there's no bed:
                If we can't "reach" the top of the pillar:
                    Jump
                Place a bed
            If the dragon's head hitbox is close enough to the bed:
                Right click the bed
        Else:
            // Perform "Default Wander" mode and avoid dragon breath.
         */
        if (_endPortalTop == null) {
            _endPortalTop = locateExitPortalTop(mod); // Don't try to execute the set Exit Portal Top task until this is loaded.
            return null;
        }else {
            ((IDragonWaiter) _whenNotPerchingTask).setExitPortalTop(_endPortalTop);
        }
	// If there is a portal, enter it.
        if (mod.getBlockTracker().anyFound(Blocks.END_PORTAL)) {
            setDebugState("Entering portal to beat the game.");
            return new DoToClosestBlockTask(
                blockPos -> new GetToBlockTask(blockPos.up(), false),
                Blocks.END_PORTAL
            );
        }
        if (!mod.getEntityTracker().entityFound(EnderDragonEntity.class)) {
            setDebugState("No dragon found.");
            return new GetToBlockTask(new BlockPos(5, 65, 0));
        }
		
	int MINIMUM_BUILDING_BLOCKS = 1;

        //Need blocks
        if (mod.getEntityTracker().entityFound(EndCrystalEntity.class) && mod.getInventoryTracker().getItemCount(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.END_STONE) < MINIMUM_BUILDING_BLOCKS || (_collectBuildMaterialsTask.isActive() && !_collectBuildMaterialsTask.isFinished(mod))) {
            if (mod.getInventoryTracker().miningRequirementMet(MiningRequirement.WOOD)) {
                mod.getBehaviour().addProtectedItems(Items.END_STONE);
                setDebugState("Collecting building blocks to pillar to crystals");
                return _collectBuildMaterialsTask;
            }
        } else {
            mod.getBehaviour().removeProtectedItems(Items.END_STONE);
        }

        //If we climbed up and kamakazed the crystal, get down to build up
        //It's to avoid getting stuck on the pillars
        if (kamakazed) {
            setDebugState("Getting down after kamakazeeing crystals");
            //equip the bot with water bucket, because baritone support MLG's
            if (mod.getInventoryTracker().hasItem(Items.WATER_BUCKET) && !mod.getInventoryTracker().isInHotBar(Items.WATER_BUCKET) && !haveEquipedWaterBucket) {
                haveEquipedWaterBucket = mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET);
            }
            //and run the tasks while the player is higher than y=64
            if (mod.getPlayer().getPos().y > 64) {  
                return new GetToYTask(63);
            } else {
                kamakazed = false;
            }
        }

        // Blow up the nearest end crystal
        if (mod.getEntityTracker().entityFound(EndCrystalEntity.class)) {
            setDebugState("Kamakazeeing crystals");
            return new DoToClosestEntityTask(
                (toDestroy) -> {
                    if (toDestroy.isInRange(mod.getPlayer(), 4)) { //If in range,
                        mod.getControllerExtras().attack(toDestroy); //destroy it
                        kamakazed = true; //and get down after that
                    }
                    haveEquipedWaterBucket = false;
                    // Go next to the crystal, arbitrary where we just need to get close.
                    return new GetToBlockTask(toDestroy.getBlockPos().add(1, 0, 0), false);
                },
                EndCrystalEntity.class
            );
        }

        //No more crystals for the dragon :)

        EnderDragonEntity dragon = mod.getEntityTracker().getTrackedEntities(EnderDragonEntity.class).get(0);

        Phase dragonPhase = dragon.getPhaseManager().getCurrent();
        boolean perching = dragonPhase.getType() == PhaseType.LANDING || dragonPhase.isSittingOrHovering();
		
	setDebugState("Waiting for the dragon to perch...");
        if (dragon.getY() < _endPortalTop.getY() + 2) {
            // Dragon is already perched.
	    setDebugState("Waiting for the dragon to UN-perch... Too late!");
            perching = false;
        }

        ((IDragonWaiter)_whenNotPerchingTask).setPerchState(perching);

        if (perching) {
            BlockPos targetStandPosition = _endPortalTop.add(-1, -1, 0);
            BlockPos playerPosition = mod.getPlayer().getBlockPos();

            // If we're not positioned (above is OK), go there and make sure we're at the right height.
            if (_positionTask != null && _positionTask.isActive() && !_positionTask.isFinished(mod)) {
                setDebugState("Going to position for bed cycle...");
                return _positionTask;
            }
            if (playerPosition.getX() != targetStandPosition.getX()
                    || playerPosition.getZ() != targetStandPosition.getZ()
                    || playerPosition.getY() < targetStandPosition.getY()
            ) {
                _positionTask = new GetToBlockTask(targetStandPosition);
                return _positionTask;
            }

            // We're positioned. Perform bed strats!
            BlockPos bedTargetPosition = _endPortalTop.up();
            boolean bedPlaced = mod.getBlockTracker().blockIsValid(bedTargetPosition, ItemHelper.itemsToBlocks(ItemHelper.BED));
            if (!bedPlaced) {
                setDebugState("Placing bed");
                // If no bed, place bed.
                // Fire messes up our "reach" so we just assume we're good when we're above a height.
                boolean canPlace = LookHelper.getCameraPos(mod).y > bedTargetPosition.getY();
                //Optional<Rotation> placeReach = LookHelper.getReach(bedTargetPosition.down(), Direction.UP);
                if (canPlace) {
                    // Look at and place!
                    if (mod.getSlotHandler().forceEquipItem(ItemHelper.BED)) {
                        LookHelper.lookAt(mod, bedTargetPosition.down(), Direction.UP);
                        //mod.getClientBaritone().getLookBehavior().updateTarget(placeReach.get(), true);
                        //if (mod.getClientBaritone().getPlayerContext().isLookingAt(bedTargetPosition.down())) {
                        // There could be fire so eh place right away
                        mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                        //}
                    }
                } else {
                    if (mod.getPlayer().isOnGround()) {
                        // Jump
                        mod.getInputControls().tryPress(Input.JUMP);
                    }
                }
            } else {
                setDebugState("GET READY...");
                // Make sure we're standing on the ground so we don't blow ourselves up lmfao
                if (!mod.getPlayer().isOnGround()) {
                    // Wait to fall
                    return null;
                }
                // Wait for dragon head to be close enough to the bed's head...
                BlockPos bedHead = WorldHelper.getBedHead(mod, bedTargetPosition);
                assert bedHead != null;
                Vec3d headPos = dragon.head.getBoundingBox().getCenter(); // dragon.head.getPos();
                double dist = headPos.distanceTo(WorldHelper.toVec3d(bedHead));
                Debug.logMessage("Dist: " + dist);
                if (dist < DRAGON_HEAD_CLOSE_TO_BED_RANGE) {
                    // Interact with the bed.
                    return new InteractWithBlockTask(bedTargetPosition);
                }
                // Wait for it...
            }
            return null;
        }

        // While it's not perching, move randomly like the first KillEnderDragonTask
        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(
                new GoalAnd(new AvoidDragonFireGoal(), new GoalGetToBlock(getRandomWanderPos(mod)))
            );
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
	mod.getBlockTracker().stopTracking(Blocks.END_PORTAL);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof KillEnderDragonWithBedsTask;
    }

    @Override
    protected String toDebugString() {
        return "Let's beat the game!";
    }
    
    //Needed to move randomly
    private BlockPos getRandomWanderPos(AltoClef mod) {
        double RADIUS_RANGE = 45;
        double MIN_RADIUS = 7;
        BlockPos pos = null;
        int allowed = 5000;

        while (pos == null) {
            if (allowed-- < 0) {
                Debug.logWarning("Failed to find random solid ground in end, this may lead to problems.");
                return null;
            }
            double radius = MIN_RADIUS + (RADIUS_RANGE - MIN_RADIUS) * Math.random();
            double angle = Math.PI * 2 * Math.random();
            int x = (int) (radius * Math.cos(angle)),
            z = (int) (radius * Math.sin(angle));
            int y = WorldHelper.getGroundHeight(mod, x, z);
            if (y == -1) continue;
            BlockPos check = new BlockPos(x, y, z);
            if (mod.getWorld().getBlockState(check).getBlock() == Blocks.END_STONE) {
                // We found a spot!
                pos = check.up();
            }
        }
        return pos;
    }
	
    private static BlockPos locateExitPortalTop(AltoClef mod) {
        if (!mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0))) return null;
        int height = WorldHelper.getGroundHeight(mod, 0, 0, Blocks.BEDROCK);
        if (height != -1) return new BlockPos(0, height, 0);
        return null;
    }
    // Also needed to move randomly while avoiding dragon breath
    private void updateBreathCostMap(AltoClef mod) {
        _breathCostMap.clear();
        double radius = 4;
        for (AreaEffectCloudEntity cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloudEntity.class)) {
            Vec3d c = cloud.getPos();
            for (int x = (int) (c.getX() - radius); x <= (int) (c.getX() + radius); ++x) {
                for (int z = (int) (c.getZ() - radius); z <= (int) (c.getZ() + radius); ++z) {
                    BlockPos p = new BlockPos(x, cloud.getBlockPos().getY(), z);
                    double sqDist = p.getSquaredDistance(c, false);
                    if (sqDist < radius) {
                        double cost = 1000.0 / (sqDist + 1);
                        _breathCostMap.put(p, cost);
                        _breathCostMap.put(p.up(), cost);
                        _breathCostMap.put(p.down(), cost);
                    }
                }
            }
        }
    }
    private class AvoidDragonFireGoal implements Goal {
        @Override
        public boolean isInGoal(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return !_breathCostMap.containsKey(pos);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return _breathCostMap.getOrDefault(pos, 0.0);
        }
    }
}

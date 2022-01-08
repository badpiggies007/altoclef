package adris.altoclef.tasks.misc.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.misc.SleepTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasks.resources.CollectBedTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.util.csharpisbetter.TimerGame;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.entity.mob.EndermanEntity;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class BeatMinecraft2Task extends Task {
    private static final Block[] TRACK_BLOCKS = new Block[] {
            Blocks.END_PORTAL_FRAME,
            Blocks.END_PORTAL,
            Blocks.CRAFTING_TABLE // For pearl trading + gold crafting
    };
    private static final Block[] BEDS = CollectBedTask.BEDS; // For the Beatminecraft sleepTask
	
    private static final int FOOD_UNITS = 200;
    private static final int FOOD_UNITS_END = 50;
    private static final int MIN_FOOD_UNITS = 10;
    private static final int MIN_FOOD_UNITS_END = 5;
    private static final int MIN_BUILD_MATERIALS = 16;
    private static final int BUILD_MATERIALS = 128;

    private static final ItemTarget[] COLLECT_EYE_GEAR = combine(
            toItemTargets(ItemHelper.DIAMOND_ARMORS),
            toItemTargets(Items.GOLDEN_BOOTS),
            toItemTargets(Items.DIAMOND_SWORD),
            toItemTargets(Items.DIAMOND_PICKAXE, 3),
            toItemTargets(Items.CRAFTING_TABLE)
    );
    private static final ItemTarget[] COLLECT_EYE_GEAR_MIN = combine(
            toItemTargets(ItemHelper.DIAMOND_ARMORS),
            toItemTargets(Items.GOLDEN_BOOTS),
            toItemTargets(Items.DIAMOND_SWORD),
            toItemTargets(Items.DIAMOND_PICKAXE, 1)
    );
    private static final ItemTarget[] IRON_GEAR = combine(
            toItemTargets(Items.IRON_SWORD),
            toItemTargets(Items.IRON_PICKAXE, 2)
    );
    private static final ItemTarget[] IRON_GEAR_MIN = combine(
            toItemTargets(Items.IRON_SWORD)
    );

    private static final int END_PORTAL_FRAME_COUNT = 12;
    private static final double END_PORTAL_BED_SPAWN_RANGE = 15;

    private boolean _shouldSetSpawnNearEndPortal; // If true, will make the bot sleep near the endportal before entering it
    private boolean _setspawnTaskRun = false; // set to true if bot need to setspawn near the portal
                                           // set to false when done
    private final int _targetEyesMin;
    private final int _targetEyes;
    private final int _bedsToCollect;
    private final TimerGame _bedInteractTimeout = new TimerGame(12); //A timeout for the sleepNearPortal, because it fail to sleep sometimes
    private long _timeOfDay; // The time of the day, long between 0 and 24000 set later
    private boolean _canSleep = false; //if the bot can sleep, set later
    private boolean _sleepTaskRun = false; //same utility as of the "_setspawnTaskRun" but for sleeping
	
    private BlockPos _endPortalCenterLocation;
    private boolean _ranStrongholdLocator;
    private boolean _endPortalOpened;
    private BlockPos _bedSpawnLocation;

    private int _cachedFilledPortalFrames = 0;

    private final HashMap<Item, Integer> _cachedEndItemDrops = new HashMap<>();

    private Task _foodTask;
    private Task _gearTask;
    //TaskCatalogue.getSquashedItemTask(new ItemTarget(Items.DIRT, BUILD_MATERIALS), new ItemTarget(Items.END_STONE, BUILD_MATERIALS), new ItemTarget(Items.COBBLESTONE, BUILD_MATERIALS), new ItemTarget(Items.NETHERRACK, BUILD_MATERIALS));
    private PlaceBedAndSetSpawnTask _setBedSpawnTask = new PlaceBedAndSetSpawnTask();
    private final SleepTask _sleepTask = new SleepTask();
    private final Task _locateStrongholdTask;
    private final Task _goToNetherTask = new DefaultGoToDimensionTask(Dimension.NETHER); // To keep the portal build cache.
    private boolean _collectingEyes;
    private boolean _collectingMaterials = false;
    private int _bedBeforeSleep = 0;

    public BeatMinecraft2Task(boolean setSpawnNearEndPortal, int targetEnderEyesMin, int targetEnderEyes, int bedsToCollect) {
        _shouldSetSpawnNearEndPortal = setSpawnNearEndPortal;
        _targetEyesMin = targetEnderEyesMin;
        _targetEyes = targetEnderEyes;
        _bedsToCollect = bedsToCollect;
        _locateStrongholdTask = new LocateStrongholdTask(_targetEyes);
    }

    @Override
    protected void onStart(AltoClef mod) {

        // Add a warning to make sure the user at least knows to change the settings.
        String settingsWarningTail = "in \".minecraft/altoclef_settings.json\". @gamer may break if you don't add this! (sorry!)";
        if (!ArrayUtils.contains(mod.getModSettings().getThrowawayItems(mod), Items.END_STONE)) {
            Debug.logWarning("\"end_stone\" is not part of your \"throwawayItems\" list " + settingsWarningTail);
        }
        if (!mod.getModSettings().shouldThrowawayUnusedItems()) {
            Debug.logWarning("\"throwawayUnusedItems\" is not set to true " + settingsWarningTail);
        }
        mod.getBehaviour().allowWalkingOn(blockPos -> mod.getChunkTracker().isChunkLoaded(blockPos) && mod.getWorld().getBlockState(blockPos).getBlock() == Blocks.END_PORTAL);
        mod.getBlockTracker().trackBlock(TRACK_BLOCKS);
        mod.getBlockTracker().trackBlock(ItemHelper.itemsToBlocks(ItemHelper.BED));
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.ENDER_EYE, Items.BLAZE_ROD, Items.ENDER_PEARL, Items.CRAFTING_TABLE, Items.FLINT_AND_STEEL, Items.WATER_BUCKET);
        mod.getBehaviour().addProtectedItems(ItemHelper.BED);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        /*
        //OLD

        if in the overworld:
          if end portal found:
            if end portal opened:
              @make sure we have iron gear and enough beds to kill the dragon first, considering whether that gear was dropped in the end
              @enter end portal
            else if we have enough eyes of ender:
              @fill in the end portal
          else if we have enough eyes of ender:
            @locate the end portal
          else:
            if we don't have diamond gear:
              if we have no food:
                @get a little bit of food
              @get diamond gear
            @go to the nether
        if in the nether:
          if we don't have enough blaze rods:
            @kill blazes till we do
          else if we don't have enough pearls:
            @barter with piglins till we do
          else:
            @leave the nether
        if in the end:
          if we have a bed:
            @do bed strats
          else:
            @just hit the dragon normally

        //NEW

        If in overworld and the player is higher than y=50
            if it have a bed to sleep and it can sleep
                sleep
            else if the bot slept
                get the bed back in inventory
        If we are in the nether and have golden boots
            equip it
        else if we have diamond armor not equiped
            equip it
        
        If we are in the end
            If we have bed
                KilldragonWithBedTask
            else
                Get back in overworld to get some

        If we need ender eyes
            If can craft ender eyes
                Craft it
            If in overworld
                Get gear for Ender eye journey
                Get 1 bed to sleep for when night comes
                Get food for Ender eye journey
                End cooking food if habe any non-cooked food left
                Get diamond gear
                Go to the nether
            If in nether
                If need blate rods
                    Get getBlazeRodsTask
                KillAndLootTask(Enderman)
        If in overworld :
            If portal if found
                Get iron sword
                Get water bucket
                Get diamond pickaxe if doesn't have one in iron or diamond
                Get blocks if needed
                Get beds
                Set spawn near portal
                If portal is open
                    Get into it
                Else
                    Open it
            Else
                Get beds
                Get food if doesn't have many
                Locate end portal
        If in nether :
            Get blocks if needed
            Locate strongholdtask
                
         */

        // If we are in the overword and the player is higher than y=50
        if (mod.getCurrentDimension() == Dimension.OVERWORLD && (mod.getPlayer().getPos().y > 50 || _sleepTaskRun)) {
            _timeOfDay = (mod.getWorld().getTimeOfDay() % 24000); //get the time of the day
            if (_timeOfDay > 12542 && _timeOfDay < 23460) { //if this condition is filled, we can sleep
                _canSleep = true;
            } else {
                _canSleep = false;
            }

            if (_canSleep) { //if we can sleep
                //and if we have a bed
                if (mod.getInventoryTracker().hasItem(ItemHelper.BED) || _sleepTaskRun) {
                    setDebugState("Sleeping in a bed"); //let's sleep
                    _bedBeforeSleep = mod.getInventoryTracker().getItemCount(ItemHelper.BED); //keep the numbers of bed we have before running the task
                    _sleepTaskRun = true;
                    //as we sleep in a bed, we reset our spawnpoint for the stronghold-bed
                    _bedSpawnLocation = null;
                    _shouldSetSpawnNearEndPortal = true;
                    return _sleepTask;
                }
            //we can't sleep, if we have run the task before...
            } else if (_sleepTaskRun && _bedBeforeSleep >= mod.getInventoryTracker().getItemCount(ItemHelper.BED)) {
                //we get our bed back in inventory
                return TaskCatalogue.getItemTask("bed",mod.getInventoryTracker().getItemCount(ItemHelper.BED)+1);
            } else if (_sleepTaskRun) { //and if we got our bed
                _sleepTaskRun = false; //this task is done
            }
        }


        // Equip golden boots if have one and in the nether
        if (mod.getCurrentDimension() == Dimension.NETHER) {
            if (!mod.getInventoryTracker().isArmorEquipped(Items.GOLDEN_BOOTS) && mod.getInventoryTracker().hasItem(Items.GOLDEN_BOOTS)) {
                    return new EquipArmorTask(Items.GOLDEN_BOOTS);
            }
        } else {// Else equip diamond armor if we have one
            for (Item diamond : ItemHelper.DIAMOND_ARMORS) {
                if (mod.getInventoryTracker().hasItem(diamond) && !mod.getInventoryTracker().isArmorEquipped(diamond)) {
                    return new EquipArmorTask(ItemHelper.DIAMOND_ARMORS);
                }
            }
        }
		

        // End stuff.
        if (mod.getCurrentDimension() == Dimension.END) {
	    _shouldSetSpawnNearEndPortal = true; //to be sure to re-set bed spawnpoint if we die, because sometimes, it doesn't do it.
            // If we have bed, do bed strats, otherwise punk normally.
            updateCachedEndItems(mod);
            if (mod.getInventoryTracker().hasItem(ItemHelper.BED)) { //if we have bed
                setDebugState("Erasing the dragon");
                return new KillEnderDragonWithBedsTask(new WaitForDragonAndPearlTask()); // the WaitForDragonAndPearlTask is unused for now
            }
            // I have nothing better to do, since damaging dragon with sword is not fixed...
            setDebugState("No beds, going to overword to get some beds");
            return new GetToXZTask(15, 15, Dimension.END); //get to x=15, z=15 and hope for the dragon to kill us with fireball.
        }

        // Check for end portals. Always.
        if (!endPortalOpened(mod, _endPortalCenterLocation) && mod.getCurrentDimension() == Dimension.OVERWORLD) {
            BlockPos endPortal = mod.getBlockTracker().getNearestTracking(Blocks.END_PORTAL);
            if (endPortal != null) {
                _endPortalCenterLocation = endPortal;
                _endPortalOpened = true;
            } else {
                // TODO: Test that this works, for some reason the bot gets stuck near the stronghold and it keeps "Searching" for the portal
                _endPortalCenterLocation = doSimpleSearchForEndPortal(mod);
            }
        }

        // Do we need more eyes?
        boolean noEyesPlease = (endPortalOpened(mod, _endPortalCenterLocation) || mod.getCurrentDimension() == Dimension.END);
        int filledPortalFrames = getFilledPortalFrames(mod, _endPortalCenterLocation);
        int eyesNeededMin = noEyesPlease ? 0 : _targetEyesMin - filledPortalFrames;
        int eyesNeeded    = noEyesPlease ? 0 : _targetEyes    - filledPortalFrames;
        int eyes = mod.getInventoryTracker().getItemCount(Items.ENDER_EYE);
        if (eyes < eyesNeededMin || (!_ranStrongholdLocator && _collectingEyes && eyes < eyesNeeded)) {
            _collectingEyes = true;
            return getEyesOfEnderTask(mod, eyesNeeded);
        } else {
            _collectingEyes = false;
        }
        
        // We have eyes. Locate our portal + enter.
        switch (mod.getCurrentDimension()) {
            case OVERWORLD -> {
                // If we found our end portal...
                if (endPortalFound(mod, _endPortalCenterLocation)) {
                    // Does our (current inventory) + (end dropped items inventory) satisfy (base requirements)?
                    //      If not, obtain (base requirements) - (end dropped items).
                    setDebugState("Getting equipment for End");

                    if (!hasItemOrDroppedInEnd(mod, Items.IRON_SWORD) && !hasItemOrDroppedInEnd(mod, Items.DIAMOND_SWORD)) {
                        return TaskCatalogue.getItemTask(Items.IRON_SWORD, 1);
                    }
                    if (!hasItemOrDroppedInEnd(mod, Items.WATER_BUCKET)) { //get a water bucket for MLGs
                        return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
                    }
                    if (!hasItemOrDroppedInEnd(mod, Items.IRON_PICKAXE) && !hasItemOrDroppedInEnd(mod, Items.DIAMOND_PICKAXE)) {
                        return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                    }
                    if (shouldForce(mod, getMaterialTask(mod,BUILD_MATERIALS))) {
                        setDebugState("Need some blocks");
                        return getMaterialTask(mod,BUILD_MATERIALS);
                    }
                    if (needsBuildingMaterials(mod)) {
                        setDebugState("Need some blocks");
                        return getMaterialTask(mod,BUILD_MATERIALS);
                    }
                    // Get beds before getting back in the end.
                    if (mod.getCurrentDimension() == Dimension.OVERWORLD && needsBeds(mod)) {
                        _shouldSetSpawnNearEndPortal = true;//to be sure for the spawnpoint
                        _bedSpawnLocation = null;
                        setDebugState("Getting beds before stronghold search.");
                        return getBedTask(mod);
                    }

                    if (_shouldSetSpawnNearEndPortal) { //If it need to set spawn near the portal
                        setDebugState("Setting spawn near end portal");
                        return setSpawnNearPortalTask(mod);
                    }

                    // We're as ready as we'll ever be, hop into the portal!
                    if (endPortalOpened(mod, _endPortalCenterLocation)) {
                        setDebugState("Entering End");
                        return new DoToClosestBlockTask(
                                blockPos -> new GetToBlockTask(blockPos.up()),
                                Blocks.END_PORTAL
                        );
                    } else {
                        // Open the portal! (we have enough eyes, do it)
                        setDebugState("Opening End Portal");
                        return new DoToClosestBlockTask(
                                        blockPos -> new InteractWithBlockTask(Items.ENDER_EYE, blockPos),
                                        blockPos -> !isEndPortalFrameFilled(mod, blockPos),
                                        Blocks.END_PORTAL_FRAME
                        );
                    }
                    //We get all we need before opening the portal to avoid falling in it accidentally without setting spawnpoint 
                } else {
                    _shouldSetSpawnNearEndPortal = true;

                    // Get beds before looking for portal location.
                    if (mod.getCurrentDimension() == Dimension.OVERWORLD && needsBeds(mod)) {
                        setDebugState("Getting beds before stronghold search.");
                        return getBedTask(mod);
                    }
                    
                    //if we need food, get it
                    if (shouldForce(mod, _foodTask)) {
                            setDebugState("Getting Food for Ender Eye journey");
                            return _foodTask;
                    }
                    if (mod.getInventoryTracker().totalFoodScore() < MIN_FOOD_UNITS_END) {
                            _foodTask = new CollectFoodTask(FOOD_UNITS_END);
                            return _foodTask;
                    }
					
                    // Portal Location
                    setDebugState("Locating End Portal...");
                    _ranStrongholdLocator = true;
                    return _locateStrongholdTask;
                }
            }
            case NETHER -> {
                if (shouldForce(mod, getMaterialTask(mod,BUILD_MATERIALS))) {
                    setDebugState("Need some blocks");
                    return getMaterialTask(mod,BUILD_MATERIALS);
                }
                if (needsBuildingMaterials(mod)) {
                        setDebugState("Need some blocks");
                        return getMaterialTask(mod,BUILD_MATERIALS);
                }
                setDebugState("Locating End Portal...");
                return _locateStrongholdTask;
            }
        }

        return null;
    }

    //Check if we need build materials
    private boolean needsBuildingMaterials(AltoClef mod) {
        if (_collectingMaterials) {
            boolean res = mod.getInventoryTracker().getItemCount(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.END_STONE) < BUILD_MATERIALS;
            if (!res) {
                    _collectingMaterials = false;
            }
            return res;
        } else {
            boolean res = mod.getInventoryTracker().getItemCount(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.END_STONE) < MIN_BUILD_MATERIALS;
            if (res) {
                    _collectingMaterials = true;
            }
            return res;
        }
    }

    private void updateCachedEndItems(AltoClef mod) {
        _cachedEndItemDrops.clear();
        for (ItemEntity entity : mod.getEntityTracker().getDroppedItems()) {
            Item item = entity.getStack().getItem();
            int count = entity.getStack().getCount();
            _cachedEndItemDrops.put(item, _cachedEndItemDrops.getOrDefault(item, 0) + count);
        }
    }
    private int getEndCachedCount(Item item) {
        return _cachedEndItemDrops.getOrDefault(item, 0);
    }
    private boolean droppedInEnd(Item item) {
        return getEndCachedCount(item) > 0;
    }
    private boolean hasItemOrDroppedInEnd(AltoClef mod, Item item) {
        return mod.getInventoryTracker().hasItem(item) || droppedInEnd(item);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(TRACK_BLOCKS);
        mod.getBlockTracker().stopTracking(ItemHelper.itemsToBlocks(ItemHelper.BED));
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BeatMinecraft2Task;
    }

    @Override
    protected String toDebugString() {
        return "Beating the Game.";
    }

    private boolean endPortalFound(AltoClef mod, BlockPos endPortalCenter) {
        if (endPortalCenter == null) {
            return false;
        }
        if (endPortalOpened(mod, endPortalCenter)) {
            return true;
        }
        return getFrameBlocks(endPortalCenter).stream().allMatch(frame -> mod.getBlockTracker().blockIsValid(frame, Blocks.END_PORTAL_FRAME));
    }
    private boolean endPortalOpened(AltoClef mod, BlockPos endPortalCenter) {
        return _endPortalOpened && endPortalCenter != null && mod.getBlockTracker().blockIsValid(endPortalCenter, Blocks.END_PORTAL);
    }
    private boolean spawnSetNearPortal(AltoClef mod, BlockPos endPortalCenter) {
        return _bedSpawnLocation != null && mod.getBlockTracker().blockIsValid(_bedSpawnLocation, ItemHelper.itemsToBlocks(ItemHelper.BED));
    }
    private int getFilledPortalFrames(AltoClef mod, BlockPos endPortalCenter) {
        // If we have our end portal, this doesn't matter.
        if (endPortalFound(mod, endPortalCenter)) {
            return END_PORTAL_FRAME_COUNT;
        }
        if (endPortalFound(mod, endPortalCenter)) {
            List<BlockPos> frameBlocks = getFrameBlocks(endPortalCenter);
            // If EVERY portal frame is loaded, consider updating our cached filled portal count.
            if (frameBlocks.stream().allMatch(blockPos -> mod.getChunkTracker().isChunkLoaded(blockPos))) {
                _cachedFilledPortalFrames = frameBlocks.stream().reduce(0, (count, blockPos) ->
                        count + (isEndPortalFrameFilled(mod, blockPos) ? 1 : 0),
                        Integer::sum);
            }
            return _cachedFilledPortalFrames;
        }
        return 0;
    }
    private static List<BlockPos> getFrameBlocks(BlockPos endPortalCenter) {
        Vec3i[] frameOffsets = new Vec3i[] {
                new Vec3i(2, 0, 1),
                new Vec3i(2, 0, 0),
                new Vec3i(2, 0, -1),
                new Vec3i(-2, 0, 1),
                new Vec3i(-2, 0, 0),
                new Vec3i(-2, 0, -1),
                new Vec3i(1, 0, 2),
                new Vec3i(0, 0, 2),
                new Vec3i(-1, 0, 2),
                new Vec3i(1, 0, -2),
                new Vec3i(0, 0, -2),
                new Vec3i(-1, 0, -2)
        };
        return Arrays.stream(frameOffsets).map(endPortalCenter::add).toList();
    }

    private Task getMaterialTask(AltoClef mod, int count) {
        //Custom task to get materials if we need some
        if (mod.getCurrentDimension() == Dimension.OVERWORLD) {
            return TaskCatalogue.getItemTask("cobblestone",count);
        } else if (mod.getCurrentDimension() == Dimension.NETHER) {
            return TaskCatalogue.getItemTask("netherrack",count);
        }
        return TaskCatalogue.getItemTask("end_stone",count);
    }

    private Task getEyesOfEnderTask(AltoClef mod, int targetEyes) {
        if (mod.getEntityTracker().itemDropped(Items.ENDER_EYE)) {
            setDebugState("Picking up Dropped Eyes");
            return new PickupDroppedItemTask(Items.ENDER_EYE, targetEyes);
        }

        int eyeCount = mod.getInventoryTracker().getItemCount(Items.ENDER_EYE);

        int blazePowderCount = mod.getInventoryTracker().getItemCount(Items.BLAZE_POWDER);
        int blazeRodCount = mod.getInventoryTracker().getItemCount(Items.BLAZE_ROD);
        int blazeRodTarget = (int)Math.ceil(((double)targetEyes - eyeCount - blazePowderCount) / 2.0);
        int enderPearlTarget = targetEyes - eyeCount;
        boolean needsBlazeRods = blazeRodCount < blazeRodTarget;
        boolean needsBlazePowder = eyeCount + blazePowderCount < targetEyes;
        boolean needsEnderPearls = mod.getInventoryTracker().getItemCount(Items.ENDER_PEARL) < enderPearlTarget;

        if (needsBlazePowder && !needsBlazeRods) {
            // We have enough blaze rods.
            setDebugState("Crafting blaze powder");
            return TaskCatalogue.getItemTask(Items.BLAZE_POWDER, targetEyes - eyeCount);
        }

        if (!needsBlazePowder && !needsEnderPearls) {
            // Craft ender eyes
            setDebugState("Crafting Ender Eyes");
            return TaskCatalogue.getItemTask(Items.ENDER_EYE, targetEyes);
        }

        // Get blaze rods + pearls...
        switch (mod.getCurrentDimension()) {
            case OVERWORLD -> {
                // Make sure we have gear, then food.

                if (shouldForce(mod, _gearTask)) {
                    setDebugState("Getting gear for Ender Eye journey");
                    return _gearTask;
                }
				
                if (shouldForce(mod, _foodTask)) {
                    setDebugState("Getting Food for Ender Eye journey");
                    return _foodTask;
                }

                // Start with iron
                if (!mod.getInventoryTracker().targetsMet(IRON_GEAR_MIN) && !mod.getInventoryTracker().targetsMet(COLLECT_EYE_GEAR_MIN)) {
                    _gearTask = TaskCatalogue.getSquashedItemTask(IRON_GEAR);
                    return _gearTask;
                }
				
                // Then get a bed before food
                if (!mod.getInventoryTracker().hasItem(ItemHelper.BED)) {
                        setDebugState("Getting a bed to sleep when night comes");
                        return TaskCatalogue.getItemTask("bed",1);
                }

                /* // We will get beds later, as it took space in inventory
                if (needsBeds(mod) && anyBedsFound(mod)) {
                    setDebugState("A bed was found, grabbing that first.");
                    return getBedTask(mod);
                }*/

                // Now, let's get some food
                if (mod.getInventoryTracker().totalFoodScore() < MIN_FOOD_UNITS) {
                    _foodTask = new CollectFoodTask(FOOD_UNITS);
                    return _foodTask;
                }

                //Sometimes, after getting interupted by skeletons or something else, it doesn't end the cooking
                if (CollectFoodTask.calculateFoodPotential(mod) != mod.getInventoryTracker().totalFoodScore()) {//if we can still cook some food
                    _foodTask = new CollectFoodTask(CollectFoodTask.calculateFoodPotential(mod)); //cook the food left
                    return _foodTask;
                }

                // Then get diamond
                if (!mod.getInventoryTracker().targetsMet(COLLECT_EYE_GEAR_MIN)) {
                    _gearTask = TaskCatalogue.getSquashedItemTask(COLLECT_EYE_GEAR);
                    return _gearTask;
                }
                // Then go to the nether.
                setDebugState("Going to Nether");
                return _goToNetherTask;
            }
            case NETHER -> {
                if (needsBlazeRods) {
                    setDebugState("Getting Blaze Rods, "+blazeRodCount+"/"+blazeRodTarget);
                    return getBlazeRodsTask(mod, blazeRodTarget);
                }
                setDebugState("Getting Ender Pearls, "+mod.getInventoryTracker().getItemCount(Items.ENDER_PEARL)+"/"+enderPearlTarget);
                //return getEnderPearlTask(mod, enderPearlTarget); //i think it's slower than farming enderman in the nether
		return new KillAndLootTask(EndermanEntity.class, new ItemTarget(Items.ENDER_PEARL, enderPearlTarget)); // great if we find a warped forest
            }
            case END -> throw new UnsupportedOperationException("You're in the end. Don't collect eyes here.");
        }
        return null;
    }

    private Task setSpawnNearPortalTask(AltoClef mod) {
        if (_setBedSpawnTask.isSpawnSet()) {
            _bedSpawnLocation = _setBedSpawnTask.getBedSleptPos();
        }
        if (shouldForce(mod, _setBedSpawnTask)) {
            // Set spawnpoint and set our bed spawn when it happens.
            setDebugState("Setting spawnpoint now.");
            return _setBedSpawnTask;
        }
        
        //If we need to setspawn and the timer is not elapsed
	if (!_bedInteractTimeout.elapsed() && _setspawnTaskRun) {
            return _setBedSpawnTask; //rerun the task, to be sure it's set
        } else if (_setspawnTaskRun) { //if it's elapsed
            //assuming it's good to go
            _setspawnTaskRun = false;
            Debug.logMessage("SET UP BED TIMEOUT / DONE");
            _bedInteractTimeout.reset();
            _shouldSetSpawnNearEndPortal = false;
        }
		
        // Get close to portal. If we're close enough, set our bed spawn somewhere nearby.
        if (_endPortalCenterLocation.isWithinDistance(mod.getPlayer().getPos(), END_PORTAL_BED_SPAWN_RANGE)) {
            _bedInteractTimeout.reset();
            _setspawnTaskRun = true;
            return _setBedSpawnTask;
        } else {
            setDebugState("Approaching portal");
            return new GetCloseToBlockTask(_endPortalCenterLocation);
        }
    }

    private Task getBlazeRodsTask(AltoClef mod, int count) {
        return new CollectBlazeRodsTask(count);
    }
    private Task getEnderPearlTask(AltoClef mod, int count) {
        // Equip golden boots before trading...
        if (!mod.getInventoryTracker().isArmorEquipped(Items.GOLDEN_BOOTS)) {
            return new EquipArmorTask(Items.GOLDEN_BOOTS);
        }
        int goldBuffer = 32;
        if (!mod.getInventoryTracker().hasItem(Items.CRAFTING_TABLE) && mod.getInventoryTracker().getItemCount(Items.GOLD_INGOT) >= goldBuffer && mod.getBlockTracker().anyFound(Blocks.CRAFTING_TABLE)) {
            setDebugState("Getting crafting table ");
            return TaskCatalogue.getItemTask(Items.CRAFTING_TABLE, 1);
        }
        return new TradeWithPiglinsTask(32, Items.ENDER_PEARL, count);
    }

    private int getTargetBeds(AltoClef mod) {
        boolean needsToSetSpawn = _shouldSetSpawnNearEndPortal &&
                (
                        !spawnSetNearPortal(mod, _endPortalCenterLocation)
                                && !shouldForce(mod, _setBedSpawnTask)
                );
        return _bedsToCollect + (needsToSetSpawn ? 1 : 0);
    }
    private boolean needsBeds(AltoClef mod) {
        return mod.getInventoryTracker().getItemCount(ItemHelper.BED) < getTargetBeds(mod)-1; //minus 1 because of the bed we need to leave in the stronghold
    }
    private Task getBedTask(AltoClef mod) {
        int targetBeds = getTargetBeds(mod);
        // Collect beds. If we want to set our spawn, collect 1 more.
        setDebugState("Collecting " + targetBeds + "beds");
        if (!mod.getInventoryTracker().hasItem(Items.SHEARS) && !anyBedsFound(mod)) {
            return TaskCatalogue.getItemTask(Items.SHEARS, 1);
        }
        return TaskCatalogue.getItemTask("bed", targetBeds);
    }
    private boolean anyBedsFound(AltoClef mod) {
        return mod.getBlockTracker().anyFound(ItemHelper.itemsToBlocks(ItemHelper.BED));
    }

    private BlockPos doSimpleSearchForEndPortal(AltoClef mod) {
        List<BlockPos> frames = mod.getBlockTracker().getKnownLocations(Blocks.END_PORTAL_FRAME);
        if (frames.size() >= END_PORTAL_FRAME_COUNT) {
            // Get the center of the frames.
            Vec3d average = frames.stream()
                    .reduce(Vec3d.ZERO, (accum, bpos) -> accum.add(bpos.getX() + 0.5, bpos.getY() + 0.5, bpos.getZ() + 0.5), Vec3d::add)
                    .multiply(1.0f / frames.size());
            return new BlockPos(average.x, average.y, average.z);
        }
        return null;
    }

    private static boolean isEndPortalFrameFilled(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos))
            return false;
        BlockState state = mod.getWorld().getBlockState(pos);
        if (state.getBlock() != Blocks.END_PORTAL_FRAME) {
            Debug.logWarning("BLOCK POS " + pos + " DOES NOT CONTAIN END PORTAL FRAME! This is probably due to a bug/incorrect assumption.");
            return false;
        }
        return state.get(EndPortalFrameBlock.EYE);
    }

    // Just a helpful utility to reduce reuse recycle.
    private static boolean shouldForce(AltoClef mod, Task task) {
        return task != null && task.isActive() && !task.isFinished(mod);
    }
    private static ItemTarget[] toItemTargets(Item ...items) {
        return Arrays.stream(items).map(item -> new ItemTarget(item, 1)).toArray(ItemTarget[]::new);
    }
    private static ItemTarget[] toItemTargets(Item item, int count) {
        return new ItemTarget[] {new ItemTarget(item, count)};
    }
    private static ItemTarget[] combine(ItemTarget[] ...targets) {
        List<ItemTarget> result = new ArrayList<>();
        for (ItemTarget[] ts : targets) {
            result.addAll(Arrays.asList(ts));
        }
        return result.toArray(ItemTarget[]::new);
    }
}

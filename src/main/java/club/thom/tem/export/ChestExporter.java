package club.thom.tem.export;

import club.thom.tem.TEM;
import club.thom.tem.listeners.LocationListener;
import club.thom.tem.listeners.packets.PacketEventListener;
import club.thom.tem.listeners.packets.events.*;
import club.thom.tem.models.export.StoredItemLocation;
import club.thom.tem.highlight.BlockHighlighter;
import com.google.common.collect.ImmutableSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.BlockPos;

public class ChestExporter implements PacketEventListener {
    private final ImmutableSet<String> exportableContainerNames = ImmutableSet.of(
            "Large Chest", "Chest", "Personal Vault", "Sack of Sacks", "Pets", "Player Inventory", "Backpack",
            "Ender Chest", "Accessory Bag", "Wardrobe", "Time Pocket", "Your Equipment and Stats", "Hopper",
            "Dropper", "Dispenser", "Furnace"
    );

    ItemExporter exporter;
    int[] lastChestCoordinates = new int[3];
    int[] lastRightClickCoordinates = new int[3];
    int[] lastEntityRightClickCoordinates = new int[3];
    long lastContainerRightClickTime = 0;
    long lastChestUpdateTime = 0;
    long lastEntityRightClickTime = 0;
    LocationListener locationListener;
    TEM tem;
    BlockHighlighter highlighter = null;

    public ChestExporter(ItemExporter exporter, TEM tem) {
        this.exporter = exporter;
        this.tem = tem;
        this.locationListener = tem.getLocationListener();
    }

    private BlockHighlighter getHighlighter() {
        if (highlighter == null) {
            highlighter = tem.getBlockHighlighter();
        }
        return highlighter;
    }

    private boolean shouldExportContainer(String containerName) {
        if (containerName.endsWith(" Recipe")) {
            return false;
        }
        return exportableContainerNames.contains(containerName) ||
                containerName.contains("Backpack") || containerName.startsWith("Pets") ||
                containerName.startsWith("Ender Chest") || containerName.startsWith("Accessory Bag") ||
                containerName.startsWith("Wardrobe") || containerName.contains("Chest");
    }

    private boolean isFurnitureChest(String containerName) {
        if (exportableContainerNames.contains(containerName)) {
            return false;
        }
        return containerName.contains("Chest");
    }

    @Override
    public void onServerSetSlotInGui(ServerSetSlotInGuiEvent event) {
        processItems(event.getWindowId(), event.getSlotNumber(), event.getItem());
    }

    @Override
    public void onServerSetItemsInGui(ServerSetItemsInGuiEvent event) {
        processItems(event.getWindowId(), -1, event.getItemStacks());
    }

    @Override
    public void onClientPlayerEntityAction(ClientPlayerEntityActionEvent event) {
        // May have right-clicked a "furniture chest".
        if (Minecraft.getMinecraft().theWorld == null || event.getAction() != C02PacketUseEntity.Action.INTERACT_AT) {
            return;
        }
        if (event.getEntityID() == -1) {
            // Unknown entity.
            return;
        }
        Entity entityClicked = Minecraft.getMinecraft().theWorld.getEntityByID(event.getEntityID());
        if (entityClicked == null) {
            return;
        }
        lastEntityRightClickCoordinates[0] = (int) entityClicked.posX;
        lastEntityRightClickCoordinates[1] = (int) entityClicked.posY;
        lastEntityRightClickCoordinates[2] = (int) entityClicked.posZ;
        lastEntityRightClickTime = System.currentTimeMillis();
    }

    @Override
    public void onClientPlayerRightClickBlock(ClientPlayerRightClickBlockEvent event) {
        if (Minecraft.getMinecraft().theWorld == null) {
            return;
        }
        BlockPos eventBlockPos = new BlockPos(event.getBlockPos()[0], event.getBlockPos()[1], event.getBlockPos()[2]);
        Block block = Minecraft.getMinecraft().theWorld.getBlockState(eventBlockPos).getBlock();
        if (!(block instanceof BlockContainer)) {
            return;
        }
        System.arraycopy(event.getBlockPos(), 0, lastRightClickCoordinates, 0, 3);
        lastContainerRightClickTime = System.currentTimeMillis();
        if (!exporter.isExporting()) {
            return;
        }
        TileEntity tileEntity = Minecraft.getMinecraft().theWorld.getTileEntity(eventBlockPos);
        if (tileEntity instanceof TileEntityChest) {
            getHighlighter().excludeChest((TileEntityChest) tileEntity);
        }
    }

    @Override
    public void onServerBlockUpdate(ServerBlockUpdateEvent event) {
        if (Minecraft.getMinecraft().theWorld == null) {
            return;
        }
        if (!(event.getBlock() instanceof BlockContainer)) {
            return;
        }

        System.arraycopy(event.getBlockPosition(), 0, lastChestCoordinates, 0, 3);
        lastChestUpdateTime = System.currentTimeMillis();
    }

    private void processItems(int windowId, int slot, ItemStack... items) {
        String profileId = tem.getProfileIdListener().getProfileId();
        if (profileId == null) {
            return;
        }

        if (!exporter.exportEnabled()) {
            return;
        }
        if (windowId != Minecraft.getMinecraft().thePlayer.openContainer.windowId) {
            return;
        }
        long nonPlayerSlots = Minecraft.getMinecraft().thePlayer.openContainer.inventorySlots.stream().filter(s -> !(s.inventory instanceof InventoryPlayer)).count();
        if (slot >= nonPlayerSlots) {
            return;
        }

        String lastMap = locationListener.getLastMap();

        if (!lastMap.equalsIgnoreCase("Private Island")) {
            return;
        }
        int[] coords = lastChestCoordinates;
        long worldInteractionTime = lastChestUpdateTime;
        if (System.currentTimeMillis() - lastChestUpdateTime > 500) {
            coords = lastRightClickCoordinates;
            worldInteractionTime = lastContainerRightClickTime;
        }

        String locationString = getContainerName();
        if (!shouldExportContainer(locationString)) {
            return;
        }

        if (isFurnitureChest(locationString)) {
            coords = lastEntityRightClickCoordinates;
            worldInteractionTime = lastEntityRightClickTime;
        }

        StoredItemLocation location;

        if (System.currentTimeMillis() - worldInteractionTime < 500) {
            locationString = String.format("%s @ %d,%d,%d on %s", locationString, coords[0], coords[1], coords[2], lastMap);
            location = new StoredItemLocation(profileId, getContainerName(), coords);
        } else {
            location = new StoredItemLocation(profileId, getContainerName(), null);
        }

        int i = 0;
        for (ItemStack item : items) {
            StoredItemLocation thisItemLocation = location;
            if (i >= nonPlayerSlots) {
                locationString = "Player Inventory";
                thisItemLocation = new StoredItemLocation(profileId, "Player Inventory", null);
            }
            i++;
            if (item == null) {
                continue;
            }
            if (exporter.shouldAlwaysExport() && locationListener.isOnOwnIsland()) {
                tem.getLocalDatabase().getUniqueItemService().queueStoreItem(item, thisItemLocation);
            }

            ExportableItem exportableItem = new ExportableItem(locationString, item, tem);

            if (exporter.isExporting()) {
                exporter.addItem(exportableItem);
            }
        }
    }

    private String getContainerName() {
        if (Minecraft.getMinecraft().thePlayer == null || Minecraft.getMinecraft().thePlayer.openContainer == null ||
                Minecraft.getMinecraft().thePlayer.openContainer.inventorySlots == null || Minecraft.getMinecraft().thePlayer.openContainer.inventorySlots.size() == 0) {
            return "Unknown Container";
        }
        return Minecraft.getMinecraft().thePlayer.openContainer.inventorySlots.get(0).inventory.getName();
    }
}

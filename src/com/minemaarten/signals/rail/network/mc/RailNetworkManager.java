package com.minemaarten.signals.rail.network.mc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import com.minemaarten.signals.Signals;
import com.minemaarten.signals.api.access.ISignal.EnumLampStatus;
import com.minemaarten.signals.network.NetworkHandler;
import com.minemaarten.signals.network.PacketClearNetwork;
import com.minemaarten.signals.network.PacketSpawnParticle;
import com.minemaarten.signals.network.PacketUpdateNetwork;
import com.minemaarten.signals.rail.network.NetworkObject;
import com.minemaarten.signals.rail.network.NetworkRail;
import com.minemaarten.signals.rail.network.NetworkState;
import com.minemaarten.signals.rail.network.NetworkUpdater;
import com.minemaarten.signals.rail.network.RailNetwork;
import com.minemaarten.signals.tileentity.TileEntityBase;

public class RailNetworkManager{

    private static final RailNetworkManager CLIENT_INSTANCE = new RailNetworkManager();
    private static final RailNetworkManager SERVER_INSTANCE = new RailNetworkManager();

    public static RailNetworkManager getInstance(){
        return FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT ? CLIENT_INSTANCE : SERVER_INSTANCE;
    }

    private RailNetwork<MCPos> network = new RailNetwork<MCPos>(Collections.emptyMap());
    private NetworkState<MCPos> state = new NetworkState<>(Collections.emptySet());
    private final NetworkUpdater<MCPos> networkUpdater = new NetworkUpdater<>(new NetworkObjectProvider());

    private RailNetworkManager(){

    }

    private void validateOnServer(){
        if(this == CLIENT_INSTANCE) throw new IllegalStateException();
    }

    /**
     * The initial nodes used to build out the network from.
     * Signals, Station Markers, rail links
     * @return
     * TODO remove after testing
     */
    private Set<NetworkRail<MCPos>> getStartNodes(){
        Set<NetworkRail<MCPos>> nodes = new HashSet<>();
        for(World world : DimensionManager.getWorlds()) {
            for(TileEntity te : world.loadedTileEntityList) {
                if(te instanceof TileEntityBase) { //Any Signals TE for testing purposes
                    for(EnumFacing facing : EnumFacing.HORIZONTALS) {
                        BlockPos pos = te.getPos().offset(facing);
                        NetworkObject<MCPos> networkObject = new NetworkObjectProvider().provide(world, pos);
                        if(networkObject instanceof MCNetworkRail) {
                            nodes.add((MCNetworkRail)networkObject);
                        }
                    }
                }
            }
        }
        return nodes;
    }

    public void initialize(){
        validateOnServer();
        initializeNetwork();
        updateState();
    }

    private void initializeNetwork(){

        NetworkObjectProvider objProvider = new NetworkObjectProvider();
        Set<NetworkRail<MCPos>> railsToTraverse = getStartNodes();
        Set<NetworkObject<MCPos>> allNetworkObjects = new HashSet<>(railsToTraverse);
        while(!railsToTraverse.isEmpty()) {
            Iterator<NetworkRail<MCPos>> iterator = railsToTraverse.iterator();
            NetworkRail<MCPos> curRail = iterator.next();
            iterator.remove();

            for(MCPos neighborPos : curRail.getPotentialNeighborRailLocations()) {
                NetworkObject<MCPos> neighbor = objProvider.provide(neighborPos);
                if(neighbor != null && allNetworkObjects.add(neighbor) && neighbor instanceof NetworkRail) {
                    railsToTraverse.add((NetworkRail<MCPos>)neighbor);
                }
            }
        }

        NetworkHandler.sendToAll(new PacketClearNetwork());
        NetworkHandler.sendToAll(new PacketUpdateNetwork(allNetworkObjects));
        network = new RailNetwork<MCPos>(allNetworkObjects);
    }

    private void updateState(){
        List<EntityMinecart> carts = new ArrayList<>();

        for(World world : DimensionManager.getWorlds()) {
            for(Entity entity : world.loadedEntityList) {
                if(entity instanceof EntityMinecart) {
                    carts.add((EntityMinecart)entity);
                }
            }
        }

        state = new NetworkState<>(new NetworkObjectProvider().provideTrains(carts));
        state.updateSignalStatusses(network);
    }

    public RailNetwork<MCPos> getNetwork(){
        return network;
    }

    public EnumLampStatus getLampStatus(World world, BlockPos pos){
        return state.getLampStatus(new MCPos(world, pos));
    }

    public void markDirty(MCPos pos){
        validateOnServer();
        networkUpdater.markDirty(pos);
    }

    public void onPreServerTick(){
        Collection<NetworkObject<MCPos>> updates = networkUpdater.getNetworkUpdates(network);
        if(!updates.isEmpty()) {
            for(NetworkObject<MCPos> obj : updates) {
                //TODO remove
                NetworkHandler.sendToAll(new PacketSpawnParticle(EnumParticleTypes.REDSTONE, obj.pos.getX() + 0.5, obj.pos.getY() + 0.5, obj.pos.getZ() + 0.5, 0, 0, 0));
            }

            NetworkHandler.sendToAll(new PacketUpdateNetwork(updates)); //TODO check if stuff actually changed
            applyUpdates(updates);
        }
    }

    //TODO threading?
    public void applyUpdates(Collection<NetworkObject<MCPos>> changedObjects){
        network = networkUpdater.applyUpdates(network, changedObjects);
        Signals.proxy.onRailNetworkUpdated();
    }

    public void clearNetwork(){
        network = new RailNetwork<>(Collections.emptyList());
        Signals.proxy.onRailNetworkUpdated();
    }

    public void onPostServerTick(){
        validateOnServer();
        updateState();
    }

    public void onPlayerJoin(EntityPlayerMP player){
        NetworkHandler.sendTo(new PacketClearNetwork(), player);
        NetworkHandler.sendTo(new PacketUpdateNetwork(network.railObjects.getAllNetworkObjects().values()), player);
    }
}

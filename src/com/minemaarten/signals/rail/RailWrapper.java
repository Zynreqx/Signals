package com.minemaarten.signals.rail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.BlockRailBase.EnumRailDirection;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.minemaarten.signals.api.IRail;
import com.minemaarten.signals.tileentity.TileEntityRailLink;
import com.minemaarten.signals.tileentity.TileEntitySignalBase;
import com.minemaarten.signals.tileentity.TileEntityStationMarker;

public class RailWrapper extends BlockPos{

    public final IRail rail;
    public final World world;
    public final IBlockState state;
    private Map<RailWrapper, EnumFacing> allNeighbors;
    private Map<EnumFacing, Map<RailWrapper, EnumFacing>> exitsForEntries;
    private List<TileEntityStationMarker> stationMarkers;
    private Map<EnumFacing, TileEntitySignalBase> signals;
    private Set<TileEntityRailLink> railLinks = Collections.emptySet();

    public RailWrapper(World world, BlockPos pos){
        this(world, pos, world.getBlockState(pos));
    }

    public RailWrapper(World world, BlockPos pos, IBlockState state){
        super(pos);
        this.world = world;
        this.state = state;
        rail = RailManager.getInstance().getRail(world, pos, state);
    }

    public boolean isRail(){
        return rail != null;
    }

    public boolean setRailDir(EnumRailDirection railDir){
        boolean valid = rail.getValidDirections(world, this, state).contains(railDir);
        if(valid) rail.setDirection(world, this, state, railDir);
        return valid;
    }

    public void invalidate(){
        if(allNeighbors != null) {
            for(RailWrapper neighbor : allNeighbors.keySet()) {
                if(neighbor.allNeighbors != null) neighbor.allNeighbors.remove(this); //Dereference itself
            }
        }
        for(TileEntityRailLink railLink : railLinks) {
            railLink.onLinkedRailInvalidated();
        }
    }

    public void link(TileEntityRailLink link){
        if(railLinks == Collections.EMPTY_SET) {
            railLinks = new HashSet<>();
        }
        railLinks.add(link);
    }

    public void unlink(TileEntityRailLink link){
        if(railLinks != Collections.EMPTY_SET) {
            railLinks.remove(link);
        }
    }

    public void updateStationCache(){
        stationMarkers = null;
    }

    public Set<String> getStationNames(){
        if(stationMarkers == null) {
            for(EnumFacing d : EnumFacing.values()) {
                TileEntity te = world.getTileEntity(offset(d));
                if(te instanceof TileEntityStationMarker) {
                    if(stationMarkers == null) stationMarkers = new ArrayList<>(1); //Be conservative with instantiating, as not many rails usually have multiple stations.
                    stationMarkers.add((TileEntityStationMarker)te);
                }
            }
            if(stationMarkers == null) stationMarkers = Collections.emptyList();
        }
        Set<String> stationNames = new HashSet<>(stationMarkers.size());
        for(TileEntityStationMarker marker : stationMarkers) {
            stationNames.add(marker.getStationName());
        }
        return stationNames;
    }

    public void updateSignalCache(){
        signals = null;
    }

    public void updateNeighborCache(){
        allNeighbors = null;
        exitsForEntries = null;
    }

    /**
     * The neighbors mapped from a entry direction, used in pathfinding. For example, junction rails only map east to west, and north to south,
     * the only pathable options
     * @param entryDir The rail direction going into this RailWrapper
     * @return
     */
    public Map<RailWrapper, EnumFacing> getNeighborsForEntryDir(EnumFacing entryDir){
        getNeighbors(); //Build the cache
        if(entryDir == null || entryDir == EnumFacing.DOWN) return allNeighbors;
        Map<RailWrapper, EnumFacing> exits = exitsForEntries.get(entryDir);
        if(exits == null) {
            new Throwable(String.format("NullPointer for Entry dir: %s, pos: %s", entryDir, this)).printStackTrace();
            exits = allNeighbors; //Fall back onto all neighbors.
        }
        return exits;
    }

    /**
     * All the neighbors of this rail, used in determining which rails are part of a block.
     * For junction rails this would include all north, south east and west.
     * @return
     */
    public Map<RailWrapper, EnumFacing> getNeighbors(){
        if(allNeighbors == null) {
            EnumSet<EnumRailDirection> validRailDirs = rail.getValidDirections(world, this, state);
            this.allNeighbors = calculateAllNeighbors(validRailDirs);
            this.exitsForEntries = calculateExitsForEntries(validRailDirs);
        }
        return allNeighbors;
    }

    private Map<EnumFacing, Map<RailWrapper, EnumFacing>> calculateExitsForEntries(EnumSet<EnumRailDirection> validRailDirs){
        Map<EnumFacing, Map<RailWrapper, EnumFacing>> exitsForEntries = new HashMap<>();

        //Evaluate every neighbor dir
        for(EnumFacing entry : allNeighbors.values()) {
            Map<RailWrapper, EnumFacing> exitsForEntry = new HashMap<>(6);
            exitsForEntries.put(entry.getOpposite(), exitsForEntry);

            //Check if that neighbor dir is part of a EnumRailDir, if so it's a valid entry/exit path
            for(EnumRailDirection railDir : validRailDirs) {
                EnumSet<EnumFacing> railDirDirs = getDirections(railDir);
                if(railDirDirs.contains(entry)) {

                    //If found, put all the rail dir entries in the result set
                    for(Map.Entry<RailWrapper, EnumFacing> neighbor : allNeighbors.entrySet()) {
                        if(neighbor.getValue() == EnumFacing.DOWN || railDirDirs.contains(neighbor.getValue())) {
                            exitsForEntry.put(neighbor.getKey(), neighbor.getValue());
                        }
                    }
                }
            }
        }

        return exitsForEntries;
    }

    private Map<RailWrapper, EnumFacing> calculateAllNeighbors(EnumSet<EnumRailDirection> validRailDirs){
        Map<RailWrapper, EnumFacing> neighbors = new HashMap<>(6);

        EnumSet<EnumFacing> validDirs = EnumSet.noneOf(EnumFacing.class);
        for(EnumRailDirection railDir : validRailDirs) {
            validDirs.addAll(getDirections(railDir));
        }

        for(EnumFacing d : validDirs) {
            if(world.isBlockLoaded(offset(d))) {
                RailWrapper rail = RailCacheManager.getInstance(world).getRail(world, offset(d));
                if(rail != null) {
                    neighbors.put(rail, d);
                } else {
                    RailWrapper rail2 = RailCacheManager.getInstance(world).getRail(world, offset(d).down());
                    if(rail2 != null) {
                        neighbors.put(rail2, d);
                    } else {
                        RailWrapper rail3 = RailCacheManager.getInstance(world).getRail(world, offset(d).up());
                        if(rail3 != null) neighbors.put(rail3, d);
                    }
                }
            }
        }

        for(EnumFacing d : EnumFacing.values()) {
            if(world.isBlockLoaded(offset(d))) {
                TileEntity te = world.getTileEntity(offset(d));
                if(te instanceof TileEntityRailLink) {
                    RailWrapper linkedNeighbor = ((TileEntityRailLink)te).getLinkedRail();
                    if(linkedNeighbor != null) {
                        neighbors.put(linkedNeighbor, EnumFacing.DOWN);
                    }
                }
            }
        }
        return neighbors;
    }

    private static EnumSet<EnumFacing> getDirections(EnumRailDirection railDir){
        switch(railDir){
            case NORTH_SOUTH:
            case ASCENDING_NORTH:
            case ASCENDING_SOUTH:
                return EnumSet.of(EnumFacing.NORTH, EnumFacing.SOUTH);
            case EAST_WEST:
            case ASCENDING_EAST:
            case ASCENDING_WEST:
                return EnumSet.of(EnumFacing.EAST, EnumFacing.WEST);
            case SOUTH_EAST:
                return EnumSet.of(EnumFacing.SOUTH, EnumFacing.EAST);
            case SOUTH_WEST:
                return EnumSet.of(EnumFacing.SOUTH, EnumFacing.WEST);
            case NORTH_WEST:
                return EnumSet.of(EnumFacing.NORTH, EnumFacing.WEST);
            case NORTH_EAST:
                return EnumSet.of(EnumFacing.NORTH, EnumFacing.EAST);
            default:
                return EnumSet.noneOf(EnumFacing.class);
        }
    }

    private static int getHeightOffset(EnumRailDirection railDir, EnumFacing dir){
        switch(railDir){
            case ASCENDING_EAST:
                return dir == EnumFacing.EAST ? 1 : 0;
            case ASCENDING_WEST:
                return dir == EnumFacing.WEST ? 1 : 0;
            case ASCENDING_NORTH:
                return dir == EnumFacing.NORTH ? 1 : 0;
            case ASCENDING_SOUTH:
                return dir == EnumFacing.SOUTH ? 1 : 0;
            default:
                return 0;
        }
    }

    public EnumRailDirection getRailDir(){
        return rail.getDirection(world, this, state);
    }

    public boolean isStraightTrack(){
        return isStraightTrack(getRailDir());
    }

    private static boolean isStraightTrack(EnumRailDirection railDir){
        switch(railDir){
            case NORTH_SOUTH:
            case ASCENDING_NORTH:
            case ASCENDING_SOUTH:
            case EAST_WEST:
            case ASCENDING_EAST:
            case ASCENDING_WEST:
                return true;
            default:
                return false;
        }
    }
}

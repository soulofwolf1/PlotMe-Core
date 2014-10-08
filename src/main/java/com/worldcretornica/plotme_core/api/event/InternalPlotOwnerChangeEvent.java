package com.worldcretornica.plotme_core.api.event;

import com.worldcretornica.plotme_core.Plot;
import com.worldcretornica.plotme_core.PlotMe_Core;
import com.worldcretornica.plotme_core.api.*;

public class InternalPlotOwnerChangeEvent extends InternalPlotEvent implements ICancellable {

    private boolean _canceled;
    private IPlayer _player;
    private String _newowner;

    public InternalPlotOwnerChangeEvent(PlotMe_Core instance, IWorld world, Plot plot, IPlayer player, String newowner) {
        super(instance, plot, world);
        _player = player;
        _newowner = newowner;
    }

    @Override
    public boolean isCancelled() {
        return _canceled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        _canceled = cancel;
    }

    public IPlayer getPlayer() {
        return _player;
    }

    public String getNewOwner() {
        return _newowner;
    }
}
package com.yourname.simpletranslate.feature.sign;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.LegacyComponentFactory;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

/** 1.12 adapter for automatic sign groups and G/H manual context selection. */
public final class SignContextSelectionManager {
    private static final int MAX_SIGNS = 100;
    private static final int MAX_SIGNS_PER_REQUEST = 4;
    private static final int MAX_SOURCE_CHARS_PER_REQUEST = 1000;
    private static final int SUBMIT_REJECTED = -1;
    private static final int SUBMIT_NONE = 0;
    private static final int SUBMIT_STARTED = 1;
    private static final int SUBMIT_CACHED = 2;
    private static final Map<BlockPos, TileEntitySign> SELECTED = new LinkedHashMap<BlockPos, TileEntitySign>();
    private static final Map<String, ITextComponent[]> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, ITextComponent[]>(256,0.75F,true){
                @Override protected boolean removeEldestEntry(Map.Entry<String,ITextComponent[]> eldest){return size()>1200;}
            });
    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<String>());
    private static final Map<String, Long> RETRY_AFTER = Collections.synchronizedMap(new LinkedHashMap<String, Long>());
    private static final long FAILURE_RETRY_MS = 6000L;
    private static boolean registered;
    private static boolean dragSelection;
    private static volatile long settingsRevision;
    private static Object seenWorld;

    private SignContextSelectionManager(){ }
    public static synchronized void register(){if(registered)return;registered=true;MinecraftForge.EVENT_BUS.register(new Events());}

    public static void toggleDragSelectionMode(){
        syncWorld();
        if(!enabled()
                ||ModConfig.CONTENT_SIGN_CONTEXT_MODE.get()!=ModConfig.SignContextMode.MANUAL){message("message.simple_translate.sign_context.manual_disabled");return;}
        dragSelection=!dragSelection;
        message(dragSelection?"message.simple_translate.sign_context.drag_started":"message.simple_translate.sign_context.drag_stopped");
        if(dragSelection)addLookedAtSign(true);
    }
    public static void tickDragSelection(){syncWorld();if(dragSelection){if(!enabled()){clearSelection();return;}addLookedAtSign(false);}}
    public static void submitSelection(){
        syncWorld();
        if(!enabled()||ModConfig.CONTENT_SIGN_CONTEXT_MODE.get()!=ModConfig.SignContextMode.MANUAL){clearSelection();message("message.simple_translate.sign_context.manual_disabled");return;}
        if(SELECTED.isEmpty()){message("message.simple_translate.sign_context.empty");return;}
        List<TileEntitySign> signs=new ArrayList<TileEntitySign>(SELECTED.values());
        int submitted=submit(signs,"sign.manual.group.by_id.direct","sign-manual-group-by-id",true);
        if(submitted==SUBMIT_REJECTED){message("message.simple_translate.sign_context.failed");return;}
        if(submitted==SUBMIT_CACHED){clearSelection();message("message.simple_translate.sign_context.success");return;}
        if(submitted==SUBMIT_NONE){message("message.simple_translate.sign_context.translating");return;}
        dragSelection=false;
        message("message.simple_translate.sign_context.translating");
    }
    public static void clearSelection(){SELECTED.clear();dragSelection=false;}

    /** Drops all world/request state while preserving the user's sign settings. */
    public static synchronized void clearRuntimeState(){
        settingsRevision++;
        clearSelection();
        PENDING.clear();
        RETRY_AFTER.clear();
        CACHE.clear();
        seenWorld=null;
    }

    public static void handleSettingsChanged(ModConfig.SignContextMode previousMode,
                                             ModConfig.SignContextMode currentMode,
                                             boolean radiusChanged){
        if(previousMode==currentMode&&!radiusChanged)return;
        com.yourname.simpletranslate.translation.TranslationEngine engine=
                com.yourname.simpletranslate.SimpleTranslateForge1122.getEngine();
        if(engine!=null){
            engine.cancelRequestSurfacePrefix("sign.auto");
            engine.cancelRequestSurfacePrefix("sign.manual");
        }
        clearRuntimeState();
    }

    public static ITextComponent[] translatedForRender(TileEntitySign sign){
        syncWorld();
        Minecraft mc=Minecraft.getMinecraft();
        if(sign==null||mc.world==null||!enabled()
                ||HoldOriginalState.isHolding(HoldOriginalFeature.SIGN))return null;
        String key=key(sign);
        ITextComponent[] cached=CACHE.get(key);
        if(cached!=null)return copy(cached);
        if(ModConfig.CONTENT_SIGN_CONTEXT_MODE.get()==ModConfig.SignContextMode.AUTO) scheduleAuto(sign);
        return null;
    }

    private static void scheduleAuto(TileEntitySign center){
        Minecraft mc=Minecraft.getMinecraft();
        RayTraceResult hit=mc.objectMouseOver;
        if(hit==null||hit.typeOfHit!=RayTraceResult.Type.BLOCK||!center.getPos().equals(hit.getBlockPos()))return;
        int radius=ModConfig.CONTENT_SIGN_RADIUS.get();
        List<TileEntitySign> nearby=new ArrayList<TileEntitySign>();
        for(TileEntity tile:mc.world.loadedTileEntityList){
            if(tile instanceof TileEntitySign&&tile!=center&&hasText((TileEntitySign)tile)
                    &&tile.getPos().distanceSq(center.getPos())<=radius*radius){
                nearby.add((TileEntitySign)tile);
            }
        }
        Collections.sort(nearby,new Comparator<TileEntitySign>(){@Override public int compare(TileEntitySign left,TileEntitySign right){return Double.compare(left.getPos().distanceSq(center.getPos()),right.getPos().distanceSq(center.getPos()));}});
        List<TileEntitySign> signs=new ArrayList<TileEntitySign>();
        if(hasText(center))signs.add(center);
        for(TileEntitySign sign:nearby){if(signs.size()>=32)break;signs.add(sign);}
        submit(signs,"sign.auto.group.by_id.direct","sign-auto-group-by-id",false);
    }

    private static int submit(final List<TileEntitySign> rawSigns,String surface,String role,final boolean manual){
        final List<TileEntitySign> signs=new ArrayList<TileEntitySign>();
        final List<String> keys=new ArrayList<String>();
        final List<BlockPos> cachedPositions=new ArrayList<BlockPos>();
        long now=System.currentTimeMillis();
        boolean cached=false;
        for(TileEntitySign sign:rawSigns){if(sign==null||sign.isInvalid()||!hasText(sign))continue;String key=key(sign);Long retry=RETRY_AFTER.get(key);if(CACHE.containsKey(key)){cached=true;cachedPositions.add(sign.getPos());continue;}if(PENDING.contains(key)||(retry!=null&&retry.longValue()>now))continue;signs.add(sign);keys.add(key);}
        if(signs.isEmpty())return cached?SUBMIT_CACHED:SUBMIT_NONE;
        com.yourname.simpletranslate.cache.TranslationBlacklist blacklist=
                com.yourname.simpletranslate.SimpleTranslateForge1122.getTranslationBlacklist();
        if(blacklist!=null){StringBuilder document=new StringBuilder();for(TileEntitySign sign:signs)for(ITextComponent line:sign.signText)document.append(line==null?"":line.getUnformattedText()).append('\n');if(blacklist.containsBlacklistedEntry(document.toString()))return SUBMIT_REJECTED;}
        if(manual)for(BlockPos position:cachedPositions)SELECTED.remove(position);
        final long requestRevision=settingsRevision;
        final long runtimeRevision=com.yourname.simpletranslate.SimpleTranslateForge1122.getRuntimeRevision();
        PENDING.addAll(keys);
        final List<SignBatch> batches=partition(signs,keys);
        final List<CompletableFuture<BatchResult>> requests=new ArrayList<CompletableFuture<BatchResult>>(batches.size());
        for(final SignBatch batch:batches){
            final List<ITextComponent> source=new ArrayList<ITextComponent>(batch.signs.size()*4);
            StringBuilder context=new StringBuilder("Sign panel. Preserve exactly four rows per sign. Positions:");
            for(TileEntitySign sign:batch.signs){context.append(' ').append(sign.getPos().toString());for(int i=0;i<4;i++)source.add(sign.signText[i]==null?LegacyComponentFactory.empty():sign.signText[i]);}
            try{
                requests.add(DirectSurfaceTranslator.translateComponentsAsync(source,surface,role,true,context.toString())
                        .handle(new java.util.function.BiFunction<ComponentListTranslationResult,Throwable,BatchResult>(){
                            @Override public BatchResult apply(ComponentListTranslationResult result,Throwable error){return new BatchResult(batch,source,result,error);}
                        }));
            }catch(RuntimeException launchFailure){requests.add(CompletableFuture.completedFuture(new BatchResult(batch,source,null,launchFailure)));}
        }
        CompletableFuture<?>[] all=requests.toArray(new CompletableFuture<?>[requests.size()]);
        CompletableFuture.allOf(all).whenComplete(new java.util.function.BiConsumer<Void,Throwable>(){
            @Override public void accept(Void ignored,Throwable aggregateError){
                Minecraft minecraft=Minecraft.getMinecraft();
                if(minecraft==null){PENDING.removeAll(keys);return;}
                minecraft.addScheduledTask(new Runnable(){@Override public void run(){
                    try{
                        if(requestRevision!=settingsRevision||!com.yourname.simpletranslate.SimpleTranslateForge1122.isRuntimeRevisionCurrent(runtimeRevision))return;
                        List<BatchResult> completed=new ArrayList<BatchResult>(requests.size());
                        boolean allAccepted=true;
                        for(CompletableFuture<BatchResult> request:requests){
                            BatchResult value;
                            try{value=request.getNow(null);}catch(RuntimeException error){value=null;}
                            if(value==null||value.error!=null||value.result==null||value.result.components==null
                                    ||value.result.components.size()!=value.source.size()
                                    ||(value.result.handled&&!value.result.translated)){allAccepted=false;break;}
                            completed.add(value);
                        }
                        if(!allAccepted){long retryAt=System.currentTimeMillis()+FAILURE_RETRY_MS;for(String key:keys)RETRY_AFTER.put(key,Long.valueOf(retryAt));if(manual)message("message.simple_translate.sign_context.failed");return;}
                        int accepted=0;
                        for(BatchResult value:completed){
                            for(int s=0;s<value.batch.signs.size();s++){
                                TileEntitySign sign=value.batch.signs.get(s);String signKey=value.batch.keys.get(s);
                                if(sign==null||sign.isInvalid()||!signKey.equals(key(sign)))continue;
                                ITextComponent[] lines=new ITextComponent[4];for(int i=0;i<4;i++)lines[i]=value.result.components.get(s*4+i);
                                CACHE.put(signKey,lines);RETRY_AFTER.remove(signKey);accepted++;
                                if(manual)SELECTED.remove(sign.getPos());
                            }
                        }
                        if(manual)message(accepted==signs.size()?"message.simple_translate.sign_context.success":accepted>0?"message.simple_translate.sign_context.partial":"message.simple_translate.sign_context.failed");
                    }finally{PENDING.removeAll(keys);}
                }});
            }
        });
        return SUBMIT_STARTED;
    }

    private static List<SignBatch> partition(List<TileEntitySign> signs,List<String> keys){
        List<SignBatch> result=new ArrayList<SignBatch>();List<TileEntitySign> currentSigns=new ArrayList<TileEntitySign>();List<String> currentKeys=new ArrayList<String>();int chars=0;
        for(int i=0;i<signs.size();i++){TileEntitySign sign=signs.get(i);int next=0;for(ITextComponent line:sign.signText)next+=line==null?0:line.getUnformattedText().length();if(!currentSigns.isEmpty()&&(currentSigns.size()>=MAX_SIGNS_PER_REQUEST||chars+next>MAX_SOURCE_CHARS_PER_REQUEST)){result.add(new SignBatch(currentSigns,currentKeys));currentSigns=new ArrayList<TileEntitySign>();currentKeys=new ArrayList<String>();chars=0;}currentSigns.add(sign);currentKeys.add(keys.get(i));chars+=next;}
        if(!currentSigns.isEmpty())result.add(new SignBatch(currentSigns,currentKeys));return result;
    }

    private static boolean hasText(TileEntitySign sign){
        if(sign==null||sign.signText==null)return false;
        for(ITextComponent line:sign.signText)if(line!=null&&!line.getUnformattedText().trim().isEmpty())return true;
        return false;
    }

    private static boolean enabled(){
        com.yourname.simpletranslate.translation.TranslationEngine engine=
                com.yourname.simpletranslate.SimpleTranslateForge1122.getEngine();
        return engine!=null&&engine.isConfigured()&&engine.isSurfaceEnabled("sign.component.direct");
    }

    private static synchronized void syncWorld(){
        Minecraft minecraft=Minecraft.getMinecraft();
        Object current=minecraft==null?null:minecraft.world;
        if(seenWorld==current)return;
        settingsRevision++;
        clearSelection();
        PENDING.clear();
        RETRY_AFTER.clear();
        CACHE.clear();
        seenWorld=current;
    }

    private static void addLookedAtSign(boolean notifyMissing){
        Minecraft mc=Minecraft.getMinecraft();RayTraceResult hit=mc.objectMouseOver;
        if(mc.world==null||hit==null||hit.typeOfHit!=RayTraceResult.Type.BLOCK){if(notifyMissing)message("message.simple_translate.sign_context.no_target");return;}
        TileEntity tile=mc.world.getTileEntity(hit.getBlockPos());if(!(tile instanceof TileEntitySign)){if(notifyMissing)message("message.simple_translate.sign_context.no_target");return;}
        if(SELECTED.containsKey(hit.getBlockPos()))return;
        if(SELECTED.size()>=MAX_SIGNS){message("message.simple_translate.sign_context.full");return;}
        SELECTED.put(hit.getBlockPos(),(TileEntitySign)tile);
        message("message.simple_translate.sign_context.added",SELECTED.size());
    }
    private static String key(TileEntitySign sign){Minecraft minecraft=Minecraft.getMinecraft();int dimension=minecraft.world==null?Integer.MIN_VALUE:minecraft.world.provider.getDimension();StringBuilder value=new StringBuilder().append(com.yourname.simpletranslate.SimpleTranslateForge1122.getRuntimeRevision()).append('\u001f').append(settingsRevision).append('\u001f').append(dimension).append('\u001f').append(sign.getPos().toString());for(ITextComponent line:sign.signText)value.append('\u001f').append(line==null?"":line.getUnformattedText());return value.toString();}
    private static ITextComponent[] copy(ITextComponent[] value){ITextComponent[] result=new ITextComponent[value.length];for(int i=0;i<value.length;i++)result[i]=value[i]==null?LegacyComponentFactory.empty():value[i].createCopy();return result;}
    private static void message(String key,Object...args){Minecraft mc=Minecraft.getMinecraft();if(mc.player!=null)mc.player.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation(key,args),true);}

    private static final class SignBatch{final List<TileEntitySign> signs;final List<String> keys;SignBatch(List<TileEntitySign> signs,List<String> keys){this.signs=Collections.unmodifiableList(new ArrayList<TileEntitySign>(signs));this.keys=Collections.unmodifiableList(new ArrayList<String>(keys));}}
    private static final class BatchResult{final SignBatch batch;final List<ITextComponent> source;final ComponentListTranslationResult result;final Throwable error;BatchResult(SignBatch batch,List<ITextComponent> source,ComponentListTranslationResult result,Throwable error){this.batch=batch;this.source=source;this.result=result;this.error=error;}}

    public static final class Events{
        @SubscribeEvent public void render(RenderWorldLastEvent event){syncWorld();if(!enabled()||SELECTED.isEmpty())return;Minecraft mc=Minecraft.getMinecraft();RenderManager manager=mc.getRenderManager();GlStateManager.enableBlend();GlStateManager.tryBlendFuncSeparate(770,771,1,0);GlStateManager.disableTexture2D();GlStateManager.depthMask(false);for(BlockPos pos:SELECTED.keySet()){AxisAlignedBB box=new AxisAlignedBB(pos).grow(0.04D).offset(-manager.viewerPosX,-manager.viewerPosY,-manager.viewerPosZ);RenderGlobal.drawSelectionBoundingBox(box,0.1F,0.85F,1.0F,1.0F);}GlStateManager.depthMask(true);GlStateManager.enableTexture2D();GlStateManager.disableBlend();}
    }
}

package dev.local.appdodge;

import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import java.text.Collator;
import java.util.*;

/** A native, searchable application list. Only visible rows load icons; no package history is stored. */
final class DailyAppPicker {
    private final MainActivity a;
    private final NativeRestConfig config;
    private final PackageManager pm;
    private final int paper,ink,muted,green,tint,line;
    private final LruCache<String,Drawable.ConstantState> icons=new LruCache<>(56);
    private final List<Entry> all=new ArrayList<>(),shown=new ArrayList<>();
    private final Map<String,String> ruleStatus=new HashMap<>();
    private static final class Entry {
        final ResolveInfo info;final String pkg,label,search;
        Entry(ResolveInfo info,String label){this.info=info;pkg=info.activityInfo.packageName;this.label=label;search=(label+" "+pkg).toLowerCase(Locale.ROOT);}
    }
    DailyAppPicker(MainActivity activity,NativeRestConfig config){
        this.a=activity;this.config=config;pm=a.getPackageManager();
        paper=a.getColor(R.color.daily_paper);
        ink=a.getColor(R.color.daily_ink);muted=a.getColor(R.color.daily_muted);
        green=a.getColor(R.color.daily_green);tint=a.getColor(R.color.daily_tint);line=a.getColor(R.color.daily_line);
    }
    private int d(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(a);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);
        if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    private void clickable(View v,Runnable f){v.setFocusable(true);v.setOnClickListener(x->f.run());v.setBackground(JournalLine.hit(a,line,0));}
    interface Pick { void accept(String pkg,String label); }
    private void inventory(String target){
        Map<String,Entry> unique=new LinkedHashMap<>();Map<String,Set<String>> owners=new HashMap<>();
        Set<String> excluded=config.essentialPackages();
        int now=Store.now();
        for(Rule r:config.rules()){
            if(target==null)ruleStatus.put(r.packageName,"已添加");
            if(target!=null&&!r.allows(now))excluded.add(r.packageName);
        }
        if(target!=null)excluded.add(target);
        else for(CoolingChange c:config.pendingChanges()){
            if("add".equals(c.kind))ruleStatus.put(c.pkg,"待添加");
            else if("remove".equals(c.kind))ruleStatus.put(c.pkg,"待移除");
            else if("edit".equals(c.kind))ruleStatus.put(c.pkg,"待修改");
        }
        for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)){
            String pkg=r.activityInfo.packageName,label=r.loadLabel(pm).toString();
            owners.computeIfAbsent(label,k->new HashSet<>()).add(pkg);
            if(excluded.contains(pkg))continue;
            if(target!=null&&(!r.activityInfo.applicationInfo.enabled||
                    (r.activityInfo.applicationInfo.flags&android.content.pm.ApplicationInfo.FLAG_SUSPENDED)!=0))continue;
            unique.putIfAbsent(pkg,new Entry(r,label));
        }
        // The icon reminder is a selectable default for every offered app: never confuse two identical labels.
        for(Entry e:unique.values())if(owners.get(e.label).size()==1)all.add(e);
        Collator col=Collator.getInstance(Locale.CHINA);all.sort((x,y)->col.compare(x.label,y.label));
    }
    void show(){show(null,a::editRule);}
    void show(String target,Pick pick){
        inventory(target);
        Dialog dialog=new Dialog(a,R.style.DailyAppTheme);Window window=dialog.getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);window.setDecorFitsSystemWindows(false);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.setStatusBarColor(paper);window.setNavigationBarColor(paper);
        LinearLayout safe=new LinearLayout(a);safe.setOrientation(LinearLayout.VERTICAL);safe.setBackgroundColor(paper);
        safe.setOnApplyWindowInsetsListener((v,in)->{android.graphics.Insets inset=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            v.setPadding(inset.left,inset.top,inset.right,inset.bottom);return in;});
        LinearLayout main=new LinearLayout(a);main.setOrientation(LinearLayout.VERTICAL);main.setPadding(d(24),d(12),d(24),0);main.setFocusableInTouchMode(true);
        safe.addView(main,new LinearLayout.LayoutParams(-1,-1));main.requestFocus();
        LinearLayout bar=new LinearLayout(a);bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(target==null?"添加应用":"选择备选应用",30,ink,true);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView back=text("返回",15,muted,false);back.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);clickable(back,dialog::dismiss);bar.addView(back,new LinearLayout.LayoutParams(d(48),d(48)));main.addView(bar);
        LinearLayout searchBar=new LinearLayout(a);searchBar.setPadding(0,0,0,0);searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(new JournalLine(a,line,1,false));
        Sign glass=new Sign(true);glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);searchBar.addView(glass,new LinearLayout.LayoutParams(d(22),d(28)));
        EditText search=new EditText(a);search.setSingleLine();search.setTextSize(16);search.setTextColor(ink);search.setHintTextColor(muted);search.setHint("搜索应用名或包名");search.setContentDescription("按应用名或包名搜索");
        search.setPadding(d(9),d(8),d(4),d(8));search.setBackgroundColor(0);search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnFocusChangeListener((v,focus)->searchBar.setBackground(new JournalLine(a,focus?green:line,focus?2:1,false)));
        searchBar.addView(search,new LinearLayout.LayoutParams(0,-2,1));
        TextView clear=text("×",23,muted,false);clear.setGravity(Gravity.CENTER);clear.setContentDescription("清除搜索");clickable(clear,()->{search.setText("");search.requestFocus();});searchBar.addView(clear,new LinearLayout.LayoutParams(d(44),d(48)));
        LinearLayout.LayoutParams fieldParams=new LinearLayout.LayoutParams(-1,-2);fieldParams.topMargin=d(10);main.addView(searchBar,fieldParams);
        LinearLayout empty=new LinearLayout(a);empty.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);empty.setOrientation(LinearLayout.VERTICAL);empty.setPadding(d(12),d(38),d(12),0);
        TextView message=text("",18,ink,true);empty.addView(message);
        FrameLayout body=new FrameLayout(a);LinearLayout.LayoutParams listSpace=new LinearLayout.LayoutParams(-1,0,1);listSpace.topMargin=d(12);main.addView(body,listSpace);
        ListView list=new ListView(a);list.setBackgroundColor(paper);list.setDivider(new ColorDrawable(line));list.setDividerHeight(d(1));list.setSelector(new ColorDrawable(tint));list.setVerticalScrollBarEnabled(false);
        body.addView(list,new FrameLayout.LayoutParams(-1,-1));body.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return shown.size();}public Entry getItem(int i){return shown.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View convert,ViewGroup parent){
                Row row=convert instanceof Row?(Row)convert:new Row();Entry e=getItem(i);row.name.setText(e.label);
                String status=ruleStatus.get(e.pkg);
                row.enrolled.setText(status);
                row.enrolled.setVisibility(status!=null?View.VISIBLE:View.GONE);
                Drawable.ConstantState cached=icons.get(e.pkg);Drawable icon=cached==null?e.info.loadIcon(pm):cached.newDrawable(a.getResources());
                if(cached==null&&icon!=null&&icon.getConstantState()!=null)icons.put(e.pkg,icon.getConstantState());row.icon.setImageDrawable(icon);
                return row;
            }
        };
        list.setAdapter(adapter);list.setOnItemClickListener((p,v,i,id)->{
            Entry e=shown.get(i);
            if(target!=null&&!config.validAlternative(target,e.pkg)){
                Toast.makeText(a,"这个备选应用当前不可用，请另选一个。",Toast.LENGTH_SHORT).show();return;
            }
            dialog.dismiss();pick.accept(e.pkg,e.label);
        });
        Runnable filter=()->{String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();
            for(Entry e:all)if(query.isEmpty()||e.search.contains(query))shown.add(e);
            adapter.notifyDataSetChanged();list.setSelection(0);
            clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);
            empty.setVisibility(shown.isEmpty()?View.VISIBLE:View.GONE);list.setVisibility(shown.isEmpty()?View.GONE:View.VISIBLE);
            if(shown.isEmpty())message.setText(query.isEmpty()?(target==null?"没有可添加的应用":"没有可选的备选应用"):"没有找到匹配的应用");
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){filter.run();}public void afterTextChanged(Editable e){}});
        filter.run();dialog.setContentView(safe);if(a.showLayer(dialog))window.setLayout(-1,-1);
    }
    private final class Row extends LinearLayout {
        final ImageView icon;final TextView name,enrolled;
        Row(){super(a);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(d(62));setPadding(0,d(8),0,d(8));
            icon=new ImageView(a);addView(icon,new LinearLayout.LayoutParams(d(37),d(37)));
            name=text("",17,ink,true);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(d(12),0,d(5),0);addView(name,new LinearLayout.LayoutParams(0,-2,1));
            enrolled=text("已添加",13,green,false);enrolled.setPadding(d(6),0,0,0);addView(enrolled);
        }
    }
    private final class Sign extends View {
        final boolean search;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Sign(boolean isSearch){super(a);search=isSearch;p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(muted);p.setStrokeWidth(d(2));}
        protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f;
            if(search){c.drawCircle(x-d(2),y-d(2),d(6),p);c.drawLine(x+d(3),y+d(3),x+d(8),y+d(8),p);}
            else{c.drawLine(x+d(4),y-d(7),x-d(3),y,p);c.drawLine(x-d(3),y,x+d(4),y+d(7),p);}
        }
    }
}

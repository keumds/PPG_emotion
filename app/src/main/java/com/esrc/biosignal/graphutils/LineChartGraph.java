package com.esrc.biosignal.graphutils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.LineDataSet.Mode;

import java.util.ArrayList;
import java.util.List;

/**
 * MPAndroidChart 기반으로 교체된 LineChartGraph
 * - 기존 AChartEngine 의존성을 모두 제거
 * - RelativeLayout 에 LineChart 뷰를 추가하는 유틸
 */
public class LineChartGraph {
    private static final int GRAPH_MODE_REAL = 0;

    private final Context mContext;
    private final RelativeLayout mLayout;
    private final LineChart mChart;

    private int mGraphMode = GRAPH_MODE_REAL;
    private int mWindowSize = 100;   // 최대 보이는 샘플 개수 (필요 시 setWindowSize로 조정)
    private int mIntervalSize = 1;   // X 간격 (정수 스텝)

    private int mCount = 0;

    private LineDataSet mDataSet;
    private LineData mData;

    public LineChartGraph(Context context, RelativeLayout layout) {
        this.mContext = context;
        this.mLayout = layout;

        // MPAndroidChart LineChart 생성 및 레이아웃에 추가
        mChart = new LineChart(context);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        mChart.setLayoutParams(lp);
        mLayout.addView(mChart);

        initChartStyle();
        initData();
    }

    private void initChartStyle() {
        // 배경/테두리
        mChart.setDrawGridBackground(false);
        mChart.setBackgroundColor(Color.TRANSPARENT);
        mChart.setNoDataText("No Data");

        // 설명/타이틀/범례
        mChart.getDescription().setEnabled(false);
        Legend legend = mChart.getLegend();
        legend.setEnabled(false);

        // X 축
        XAxis xAxis = mChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.argb(0xFF, 0x99, 0x99, 0x99));
        xAxis.setDrawGridLines(true);
        xAxis.setDrawAxisLine(true);
        xAxis.setAxisLineColor(Color.WHITE);
        xAxis.setGridColor(Color.WHITE);
        xAxis.setGranularity(1f);
        xAxis.setLabelCount(1, true); // 기존 mRenderer.setXLabels(1) 유사

        // Y 축 (왼쪽만 사용)
        YAxis left = mChart.getAxisLeft();
        left.setTextColor(Color.argb(0xFF, 0x99, 0x99, 0x99));
        left.setAxisLineColor(Color.WHITE);
        left.setGridColor(Color.WHITE);
        left.setAxisMinimum(0f);      // mRenderer.setYAxisMin(0);
        left.setAxisMaximum(1024f);   // mRenderer.setYAxisMax(1024);
        left.setLabelCount(10, true); // mRenderer.setYLabels(10)
        left.setDrawTopYLabelEntry(true);

        // 오른쪽 Y 축 사용 안 함
        mChart.getAxisRight().setEnabled(false);

        // 줌/팬 비활성
        mChart.setScaleEnabled(false);
        mChart.setPinchZoom(false);
        mChart.setDoubleTapToZoomEnabled(false);
        mChart.setDragEnabled(false);
        mChart.setTouchEnabled(false);
    }

    private void initData() {
        List<Entry> entries = new ArrayList<>();
        if (mCount == 0) {
            entries.add(new Entry(0f, 50f)); // 기존 mSeries.add(0, 50) 초기값
            mCount = 1;
        }

        mDataSet = new LineDataSet(entries, "");
        mDataSet.setLineWidth(5f); // XYSeriesRenderer.setLineWidth(10)과 유사 (px→dp 감안)
        mDataSet.setColor(Color.argb(0xFF, 0xFF, 0xBB, 0x00));
        mDataSet.setDrawCircles(false);
        mDataSet.setDrawValues(false);
        mDataSet.setMode(Mode.LINEAR);
        mDataSet.setHighlightEnabled(false);

        mData = new LineData(mDataSet);
        mChart.setData(mData);
        mChart.invalidate();
    }

    /** 새 값 추가 (실시간 그래프 용) */
    public void addValue(float y) {
        float x = mCount * 1.0f * mIntervalSize;
        mData.addEntry(new Entry(x, y), 0);
        mData.notifyDataChanged();
        mChart.notifyDataSetChanged();

        // 윈도우 크기 유지 (가로 스크롤 대신 과거 제거)
        if (mDataSet.getEntryCount() > mWindowSize) {
            // 가장 오래된 엔트리 제거
            Entry e = mDataSet.getEntryForIndex(0);
            if (e != null) {
                mDataSet.removeFirst();
            }
        }

        mChart.invalidate();
        mCount++;
    }

    /** 데이터를 모두 지움 */
    public void clear() {
        mDataSet.clear();
        mData.notifyDataChanged();
        mChart.notifyDataSetChanged();
        mChart.invalidate();
        mCount = 0;
    }

    /** X축 가시 엔트리(윈도우) 개수 설정 */
    public void setWindowSize(int windowSize) {
        if (windowSize > 0) {
            this.mWindowSize = windowSize;
        }
    }

    /** X 간격 설정(정수 스텝) */
    public void setIntervalSize(int intervalSize) {
        this.mIntervalSize = Math.max(1, intervalSize);
    }

    /** LineChart 뷰를 외부에서 필요 시 접근 */
    public LineChart getChart() {
        return mChart;
    }
}

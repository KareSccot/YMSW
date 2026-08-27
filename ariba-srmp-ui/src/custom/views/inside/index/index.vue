<template>
  <div class="dashboard-container">
    <div class="stats-cards">
      <div class="stat-card total" @click="goToPage('/supplierMasterData')">
        <div class="stat-icon">
          <i class="fa-solid fa-building"></i>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.total }}</div>
          <div class="stat-label">供应商总数</div>
        </div>
      </div>
      <div class="stat-card high-risk" @click="goToPage('/supplierRiskRankInfo?rankLevelList=高风险')">
        <div class="stat-icon">
          <i class="fa-solid fa-triangle-exclamation"></i>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.high }}</div>
          <div class="stat-label">高风险供应商</div>
        </div>
      </div>
      <div class="stat-card medium-risk" @click="goToPage('/supplierRiskRankInfo?rankLevelList=中风险')">
        <div class="stat-icon">
          <i class="fa-solid fa-circle-exclamation"></i>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.medium }}</div>
          <div class="stat-label">中风险供应商</div>
        </div>
      </div>
      <div class="stat-card low-risk" @click="goToPage('/supplierRiskRankInfo?rankLevelList=低风险')">
        <div class="stat-icon">
          <i class="fa-solid fa-check-circle"></i>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.low }}</div>
          <div class="stat-label">低风险供应商</div>
        </div>
      </div>
    </div>

    <div class="charts-row">
      <div class="chart-card">
        <div class="card-header">
          <h3 class="card-title">
            <i class="fa-solid fa-file-shield"></i>
            启信宝风险等级分布
          </h3>
        </div>
        <div class="card-body">
          <div ref="riskChartQxb" class="chart-container"></div>
        </div>
        <div class="card-footer">
          <div class="footer-item" v-for="item in qxbDistribution" :key="item.name">
            <span :class="['risk-badge', item.class]"></span>
            <span class="risk-text">{{ item.name }}</span>
            <span class="risk-count">{{ item.value }}</span>
          </div>
        </div>
      </div>

      <div class="chart-card">
        <div class="card-header">
          <h3 class="card-title">
            <i class="fa-solid fa-scale-unbalanced"></i>
            贸易合规风险等级分布
          </h3>
        </div>
        <div class="card-body">
          <div ref="riskChartTrade" class="chart-container"></div>
        </div>
        <div class="card-footer">
          <div class="footer-item" v-for="item in tradeDistribution" :key="item.name">
            <span :class="['risk-badge', item.class]"></span>
            <span class="risk-text">{{ item.name }}</span>
            <span class="risk-count">{{ item.value }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="charts-row">
      <div class="chart-card full-width">
        <div class="card-header">
          <h3 class="card-title">
            <i class="fa-solid fa-file-certificate"></i>
            供应商证书统计
          </h3>
        </div>
        <div class="card-body">
          <div ref="certChart" class="chart-container-wide"></div>
        </div>
        <div class="card-footer">
          <div class="footer-item" v-for="item in certDistribution" :key="item.name">
            <span :class="['risk-badge', item.class]"></span>
            <span class="risk-text">{{ item.name }}</span>
            <span class="risk-count">{{ item.value }}</span>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import http from '@/base/ts/api/base/axios.ts'
import { env } from '@/base/ts/utils/env.ts'

const riskChartQxb = ref<HTMLElement | null>(null)
const riskChartTrade = ref<HTMLElement | null>(null)
const certChart = ref<HTMLElement | null>(null)

let chartQxbInstance: echarts.ECharts | null = null
let chartTradeInstance: echarts.ECharts | null = null
let chartCertInstance: echarts.ECharts | null = null

const stats = reactive({
  total: 0,
  high: 0,
  medium: 0,
  low: 0
})

const qxbDistribution = ref([
  { name: '高风险', value: 0, class: 'high' },
  { name: '中风险', value: 0, class: 'medium' },
  { name: '低风险', value: 0, class: 'low' },
  { name: '未获取到', value: 0, class: 'unavailable' }
])

const tradeDistribution = ref([
  { name: '高风险', value: 0, class: 'high' },
  { name: '低风险', value: 0, class: 'low' },
  { name: '未获取到', value: 0, class: 'unavailable' }
])

const certDistribution = ref([
  { name: '全部无效', value: 0, class: 'invalid' },
  { name: '部分有效', value: 0, class: 'partial' },
  { name: '全部有效', value: 0, class: 'valid' },
  { name: '未获取到', value: 0, class: 'unavailable' }
])

const fetchDashboardStats = async () => {
  try {
    const url = env('api_prefix') + '/md/supplierRiskRankInfo/dashboardStats'
    const response = await http.get(url)
    const data = response.data?.data || {}

    stats.total = data.total || 0
    stats.high = data.rankLevelHigh || 0
    stats.medium = data.rankLevelMedium || 0
    stats.low = data.rankLevelLow || 0

    qxbDistribution.value[0].value = data.rankLevelHigh || 0
    qxbDistribution.value[1].value = data.rankLevelMedium || 0
    qxbDistribution.value[2].value = data.rankLevelLow || 0
    qxbDistribution.value[3].value = data.rankLevelNull || 0

    tradeDistribution.value[0].value = data.tcssRankLevelHigh || 0
    tradeDistribution.value[1].value = data.tcssRankLevelLow || 0
    tradeDistribution.value[2].value = data.tcssRankLevelNull || 0

    certDistribution.value[0].value = data.certInvalid || 0
    certDistribution.value[1].value = data.certPartial || 0
    certDistribution.value[2].value = data.certValid || 0
    certDistribution.value[3].value = data.certUnavailable || 0

    updateCharts()
  } catch (e) {
    console.error('获取首页统计数据失败', e)
  }
}

const initCharts = () => {
  if (riskChartQxb.value) {
    chartQxbInstance = echarts.init(riskChartQxb.value)
  }
  if (riskChartTrade.value) {
    chartTradeInstance = echarts.init(riskChartTrade.value)
  }
  if (certChart.value) {
    chartCertInstance = echarts.init(certChart.value)
  }
}

const updateCharts = () => {
  chartQxbInstance?.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 家 ({d}%)' },
    legend: { orient: 'vertical', left: 'left', top: 'center', textStyle: { fontSize: 12, color: '#666' } },
    series: [{
      name: '启信宝风险分布', type: 'pie', radius: ['45%', '75%'], center: ['55%', '50%'],
      avoidLabelOverlap: false, itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}\n{d}%', fontSize: 11, color: '#666' },
      emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' }, itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0, 0, 0, 0.2)' } },
      labelLine: { show: true },
      data: [
        { value: qxbDistribution.value[0].value, name: '高风险', itemStyle: { color: '#e74c3c' } },
        { value: qxbDistribution.value[1].value, name: '中风险', itemStyle: { color: '#f39c12' } },
        { value: qxbDistribution.value[2].value, name: '低风险', itemStyle: { color: '#27ae60' } },
        { value: qxbDistribution.value[3].value, name: '未获取到', itemStyle: { color: '#CCCCCC' } }
      ]
    }]
  })

  chartTradeInstance?.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 家 ({d}%)' },
    legend: { orient: 'vertical', left: 'left', top: 'center', textStyle: { fontSize: 12, color: '#666' } },
    series: [{
      name: '贸易合规风险分布', type: 'pie', radius: ['45%', '75%'], center: ['55%', '50%'],
      avoidLabelOverlap: false, itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}\n{d}%', fontSize: 11, color: '#666' },
      emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' }, itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0, 0, 0, 0.2)' } },
      labelLine: { show: true },
      data: [
        { value: tradeDistribution.value[0].value, name: '高风险', itemStyle: { color: '#e74c3c' } },
        { value: tradeDistribution.value[1].value, name: '低风险', itemStyle: { color: '#27ae60' } },
        { value: tradeDistribution.value[2].value, name: '未获取到', itemStyle: { color: '#CCCCCC' } }
      ]
    }]
  })

  chartCertInstance?.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '10%', containLabel: true },
    xAxis: { type: 'category', data: ['全部无效', '部分有效', '全部有效', '未获取到'], axisLabel: { fontSize: 12, color: '#666' } },
    yAxis: { type: 'value', axisLabel: { fontSize: 12, color: '#666' } },
    series: [
      {
        name: '供应商数量', type: 'bar',
        itemStyle: { borderRadius: [6, 6, 0, 0] },
        emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0, 0, 0, 0.2)' } },
        data: [
          { value: certDistribution.value[0].value, itemStyle: { color: '#e74c3c' } },
          { value: certDistribution.value[1].value, itemStyle: { color: '#f39c12' } },
          { value: certDistribution.value[2].value, itemStyle: { color: '#27ae60' } },
          { value: certDistribution.value[3].value, itemStyle: { color: '#95a5a6' } }
        ]
      }
    ]
  })
}

const goToPage = (path: string) => {
  window.location.hash = path
}

const handleResize = () => {
  chartQxbInstance?.resize()
  chartTradeInstance?.resize()
  chartCertInstance?.resize()
}

onMounted(async () => {
  initCharts()
  await fetchDashboardStats()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chartQxbInstance?.dispose()
  chartTradeInstance?.dispose()
  chartCertInstance?.dispose()
})
</script>

<style scoped lang="scss">
.dashboard-container {
  padding: 24px;
  min-height: 100%;
  background: #f5f7fa;
}

.stats-cards {
  display: flex;
  gap: 20px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}

.stat-card {
  flex: 1;
  min-width: 160px;
  max-width: 180px;
  display: flex;
  align-items: center;
  padding: 16px;
  border-radius: 10px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
  cursor: pointer;
  
  &:hover {
    transform: translateY(-4px);
    box-shadow: 0 8px 25px rgba(0, 0, 0, 0.12);
  }
  
  &.total {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    .stat-icon { background: rgba(255, 255, 255, 0.2); }
    .stat-icon i { color: #fff; }
    .stat-value, .stat-label { color: #fff; }
  }
  
  &.high-risk {
    background: linear-gradient(135deg, #ff6b6b 0%, #ee5a5a 100%);
    .stat-icon { background: rgba(255, 255, 255, 0.2); }
    .stat-icon i { color: #fff; }
    .stat-value, .stat-label { color: #fff; }
  }
  
  &.medium-risk {
    background: linear-gradient(135deg, #ffc107 0%, #ffb300 100%);
    .stat-icon { background: rgba(255, 255, 255, 0.2); }
    .stat-icon i { color: #fff; }
    .stat-value, .stat-label { color: #fff; }
  }
  
  &.low-risk {
    background: linear-gradient(135deg, #4caf50 0%, #43a047 100%);
    .stat-icon { background: rgba(255, 255, 255, 0.2); }
    .stat-icon i { color: #fff; }
    .stat-value, .stat-label { color: #fff; }
  }
  
  .stat-icon {
    width: 40px;
    height: 40px;
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-right: 12px;
    font-size: 18px;
  }
  
  .stat-content {
    .stat-value { font-size: 24px; font-weight: 700; line-height: 1.2; }
    .stat-label { font-size: 12px; opacity: 0.9; margin-top: 2px; }
  }
}

.charts-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(450px, 1fr));
  gap: 24px;
  margin-bottom: 24px;
  
  .full-width {
    grid-column: 1 / -1;
  }
}

.chart-card {
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
  overflow: hidden;
  transition: box-shadow 0.3s ease;
  
  &:hover { box-shadow: 0 8px 30px rgba(0, 0, 0, 0.1); }
  
  .card-header {
    padding: 20px 24px;
    border-bottom: 1px solid #f0f0f0;
    background: linear-gradient(135deg, #2c3e50 0%, #34495e 100%);
    display: flex;
    justify-content: space-between;
    align-items: center;
    
    .card-title {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
      color: #fff;
      display: flex;
      align-items: center;
      gap: 10px;
      i { font-size: 18px; }
    }
  }
  
  .card-body { padding: 24px; }
  
  .card-footer {
    padding: 16px 24px;
    background: #f8f9fa;
    display: flex;
    justify-content: space-around;
    border-top: 1px solid #f0f0f0;
    flex-wrap: wrap;
    gap: 12px;
    
    .footer-item {
      display: flex;
      align-items: center;
      gap: 8px;
      
      .risk-badge {
        width: 12px; height: 12px; border-radius: 50%;
        &.high { background: #e74c3c; }
        &.medium { background: #f39c12; }
        &.low { background: #27ae60; }
        &.invalid { background: #e74c3c; }
        &.partial { background: #f39c12; }
        &.valid { background: #27ae60; }
        &.unavailable { background: #95a5a6; }
      }
      
      .risk-text { font-size: 13px; color: #666; }
      .risk-count { font-size: 18px; font-weight: 700; color: #333; }
    }
    
    .heatmap-legend {
      display: flex;
      align-items: center;
      gap: 16px;
      
      .legend-label { font-size: 13px; color: #666; }
      .legend-item { display: flex; align-items: center; gap: 6px; }
      .legend-color { width: 20px; height: 20px; border-radius: 4px; }
    }
  }
}

.chart-container { height: 280px; }
.chart-container-wide { height: 320px; }
</style>
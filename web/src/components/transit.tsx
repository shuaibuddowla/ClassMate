"use client";

import { useEffect, useState, useRef, useMemo, useCallback } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "./transit.css";
import {
  Bus,
  Layers,
  MapPin,
  Clock,
  Compass,
  Radio,
  Bell,
  ChevronUp,
  ChevronDown,
  Navigation,
  Play,
  RotateCcw,
  Sparkles
} from "lucide-react";

/* ==========================================================================
   MBSTU Transit Types & Verified Data
   ========================================================================== */

export interface MbstuBusData {
  id: string;
  nameBn: string;
  nameEn: string;
  lat: number;
  lon: number;
  speed: number;
  heading: number;
  routeName: string;
  status: string;
  color: string;
  isDriving: boolean;
  lastUpdated?: string;
}

export interface CorridorStation {
  id: string;
  name: string;
  nameBn: string;
  lat: number;
  lon: number;
  order: number;
  isCampus: boolean;
}

// 6 Verified MBSTU Buses
export const INITIAL_FLEET: MbstuBusData[] = [
  { id: "SB0003", nameBn: "সোনারতরী", nameEn: "Sonartori", lat: 24.23482, lon: 89.89195, speed: 0, heading: 181, routeName: "Campus ➔ Nirala ➔ DC Lake Loop", status: "At Prottoy-71 Bus Stop", color: "#0284c7", isDriving: false },
  { id: "SB0001", nameBn: "গীতাঞ্জলি", nameEn: "Gitanjali", lat: 24.23491, lon: 89.89135, speed: 0, heading: 171, routeName: "MBSTU Campus Standby", status: "Parked at Prottoy-71 Standby", color: "#6366f1", isDriving: false },
  { id: "SB0009", nameBn: "বনলতা", nameEn: "Banolata", lat: 24.23493, lon: 89.89155, speed: 0, heading: 0, routeName: "Campus ➔ Kagmari ➔ Nirala", status: "Parked at Prottoy-71 Standby", color: "#059669", isDriving: false },
  { id: "SB0004", nameBn: "কপোতাক্ষ", nameEn: "Kopotakkho", lat: 24.23512, lon: 89.89012, speed: 0, heading: 90, routeName: "Mukto Mancho ➔ City Loop", status: "At Mukto Mancho Bus Stop", color: "#ea580c", isDriving: false },
  { id: "SB0006", nameBn: "চিত্রা", nameEn: "Chitra", lat: 24.23514, lon: 89.89036, speed: 0, heading: 90, routeName: "Mukto Mancho ➔ City Loop", status: "At Mukto Mancho Bus Stop", color: "#7c3aed", isDriving: false },
  { id: "SB0008", nameBn: "মহুয়া", nameEn: "Mohua", lat: 24.23511, lon: 89.89062, speed: 0, heading: 90, routeName: "Mukto Mancho ➔ City Loop", status: "At Mukto Mancho Bus Stop", color: "#db2777", isDriving: false }
];

// All 28 MBSTU Campus & Tangail City Stoppages
export const CORRIDOR_STATIONS: CorridorStation[] = [
  { id: "mukto_mancho", name: "Mukto Mancho Bus Stop", nameBn: "মুক্তমঞ্চ বাস স্টপ", lat: 24.23512, lon: 89.89025, order: 1, isCampus: true },
  { id: "ab1", name: "Academic Building-1 Bus Stop", nameBn: "একাডেমিক ভবন-১ বাস স্টপ", lat: 24.23715, lon: 89.89038, order: 2, isCampus: true },
  { id: "soh_hall", name: "SOH Hall Bus Stop", nameBn: "শহীদ জিয়াউর রহমান হল স্টপ", lat: 24.23835, lon: 89.89042, order: 3, isCampus: true },
  { id: "mannan_hall", name: "Mannan Hall Bus Stop", nameBn: "মান্নান হল বাস স্টপ", lat: 24.23855, lon: 89.89105, order: 4, isCampus: true },
  { id: "ab2", name: "Academic Building-2 Bus Stop", nameBn: "একাডেমিক ভবন-২ বাস স্টপ", lat: 24.23712, lon: 89.89152, order: 5, isCampus: true },
  { id: "fifth_gate", name: "Fifth Gate Bus Stop", nameBn: "পঞ্চম গেট বাস স্টপ", lat: 24.23578, lon: 89.89178, order: 6, isCampus: true },
  { id: "prottoy_71", name: "Prottoy-71 Bus Stop", nameBn: "প্রত্যয়-৭১ বাস স্টপ", lat: 24.23482, lon: 89.89195, order: 7, isCampus: true },
  { id: "first_gate", name: "First Gate Bus Stop", nameBn: "প্রথম গেট (প্রধান ফটক) বাস স্টপ", lat: 24.23378, lon: 89.89218, order: 8, isCampus: true },
  { id: "arif_nagar", name: "Arif Nagar Bus Stop", nameBn: "আরিফ নগর বাস স্টপ", lat: 24.23585, lon: 89.89525, order: 9, isCampus: false },
  { id: "shena_nagar", name: "Shena Nagar Bus Stop", nameBn: "সেনা নগর বাস স্টপ", lat: 24.23725, lon: 89.89655, order: 10, isCampus: false },
  { id: "kagmari_more", name: "Kagmari More Bus Stop", nameBn: "কাগমারী মোড় বাস স্টপ", lat: 24.23915, lon: 89.89815, order: 11, isCampus: false },
  { id: "kagmari_bridge_1", name: "Kagmari Bridge-1 Bus Stop", nameBn: "কাগমারী ব্রিজ-১ বাস স্টপ", lat: 24.24085, lon: 89.90125, order: 12, isCampus: false },
  { id: "kagmari_bridge_2", name: "Kagmari Bridge-2 Bus Stop", nameBn: "কাগমারী ব্রিজ-২ বাস স্টপ", lat: 24.24155, lon: 89.90225, order: 13, isCampus: false },
  { id: "baby_stand", name: "Baby Stand Bus Stop", nameBn: "বেবি স্ট্যান্ড বাস স্টপ", lat: 24.24415, lon: 89.90445, order: 14, isCampus: false },
  { id: "shantikunja", name: "Shantikunja More Bus Stop", nameBn: "শান্তিকুঞ্জ মোড় বাস স্টপ", lat: 24.24665, lon: 89.90735, order: 15, isCampus: false },
  { id: "noor_mosque", name: "Nirala More Bus Stop", nameBn: "নিরালা মোড় বাস স্টপ", lat: 24.24985, lon: 89.91485, order: 16, isCampus: false },
  { id: "pouro_uddan", name: "Pouro Uddan Bus Stop", nameBn: "পৌর উদ্যান বাস স্টপ", lat: 24.25125, lon: 89.91445, order: 17, isCampus: false },
  { id: "taltola", name: "Taltola-Shimultoli Road Bus Stop", nameBn: "তালতলা-শিমুলতলী রোড স্টপ", lat: 24.25285, lon: 89.91415, order: 18, isCampus: false },
  { id: "bottola", name: "Bottola Road Bus Stop", nameBn: "বটতলা রোড বাস স্টপ", lat: 24.25845, lon: 89.91395, order: 19, isCampus: false },
  { id: "dc_lake", name: "DC Lake Bus Stop", nameBn: "ডিসি লেক বাস স্টপ", lat: 24.26245, lon: 89.91545, order: 20, isCampus: false },
  { id: "district_stop", name: "District Bus Stop", nameBn: "ডিস্ট্রিক্ট বাস স্টপ", lat: 24.26195, lon: 89.91635, order: 21, isCampus: false },
  { id: "kazi_nazrul", name: "Kazi-Nazrul Sarani Bus Stop", nameBn: "কাজী নজরুল সরণি বাস স্টপ", lat: 24.26175, lon: 89.91695, order: 22, isCampus: false },
  { id: "baitun_nur", name: "Baitun Nur Jame Mosque Mor", nameBn: "বায়তুন নূর জামে মসজিদ মোড়", lat: 24.25985, lon: 89.91715, order: 23, isCampus: false },
  { id: "cnb_more", name: "C & B More Bus Stop", nameBn: "সি অ্যান্ড বি মোড় বাস স্টপ", lat: 24.25895, lon: 89.91765, order: 24, isCampus: false },
  { id: "kumudini_gate", name: "Kumudini College Gate Bus Stop", nameBn: "কুমুদিনী কলেজ গেট বাস স্টপ", lat: 24.25715, lon: 89.91885, order: 25, isCampus: false },
  { id: "registry_para", name: "Registry Para Road Bus Stop", nameBn: "রেজিস্ট্রি পাড়া রোড বাস স্টপ", lat: 24.25355, lon: 89.91965, order: 26, isCampus: false },
  { id: "puraton_stand", name: "Puraton Bus Stand Bus Stop", nameBn: "পুরাতন বাস স্ট্যান্ড স্টপ", lat: 24.25245, lon: 89.91955, order: 27, isCampus: false },
  { id: "boro_kalibari", name: "Boro Kalibari More Bus Stop", nameBn: "বড় কালীবাড়ি মোড় বাস স্টপ", lat: 24.25135, lon: 89.91665, order: 28, isCampus: false }
];

// Complete 46-vertex MBSTU Campus & Tangail City Corridor Loop
export const ROUTE_POLYLINE: [number, number][] = [
  [24.23512, 89.89025], [24.23585, 89.89038], [24.23715, 89.89038], [24.23835, 89.89042],
  [24.23872, 89.89082], [24.23855, 89.89105], [24.23815, 89.89145], [24.23712, 89.89152],
  [24.23682, 89.89172], [24.23578, 89.89178], [24.23482, 89.89195], [24.23378, 89.89218],
  [24.23415, 89.89425], [24.23585, 89.89525], [24.23725, 89.89655], [24.23885, 89.89835],
  [24.23915, 89.89815], [24.24025, 89.90045], [24.24085, 89.90125], [24.24155, 89.90225],
  [24.24225, 89.90345], [24.24415, 89.90445], [24.24665, 89.90735], [24.24845, 89.91125],
  [24.24985, 89.91485], [24.25125, 89.91445], [24.25285, 89.91415], [24.25585, 89.91365],
  [24.25725, 89.91425], [24.25845, 89.91395], [24.26025, 89.91325], [24.26195, 89.91415],
  [24.26255, 89.91485], [24.26245, 89.91545], [24.26195, 89.91635], [24.26175, 89.91695],
  [24.26135, 89.91755], [24.25985, 89.91715], [24.25895, 89.91765], [24.25715, 89.91885],
  [24.25545, 89.91965], [24.25355, 89.91965], [24.25245, 89.91955], [24.25215, 89.91785],
  [24.25135, 89.91665], [24.24985, 89.91485]
];

// Haversine Distance in Meters
export function getDistanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371e3;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
}

// Find closest vertex index on polyline
function findClosestPolylineIndex(point: [number, number], poly: [number, number][]): number {
  let minD = Infinity;
  let minIdx = 0;
  for (let i = 0; i < poly.length; i++) {
    const d = getDistanceMeters(point[0], point[1], poly[i][0], poly[i][1]);
    if (d < minD) {
      minD = d;
      minIdx = i;
    }
  }
  return minIdx;
}

/* ==========================================================================
   Main Component: Transit
   ========================================================================== */

export function Transit({ ctx }: { ctx?: any }) {
  const [fleet, setFleet] = useState<MbstuBusData[]>(INITIAL_FLEET);
  const [selectedBusId, setSelectedBusId] = useState<string>("SB0003");
  const [satelliteView, setSatelliteView] = useState<boolean>(true);
  const [sheetExpanded, setSheetExpanded] = useState<boolean>(false);
  const [isDemoMode, setIsDemoMode] = useState<boolean>(false);
  const [isLiveWs, setIsLiveWs] = useState<boolean>(false);
  const [alertedStops, setAlertedStops] = useState<{ [stopId: string]: boolean }>({});
  const [userLocation, setUserLocation] = useState<{ lat: number; lon: number } | null>(null);

  // Map & Layers Refs
  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const satLayerRef = useRef<L.TileLayer | null>(null);
  const streetLayerRef = useRef<L.TileLayer | null>(null);
  const busMarkersRef = useRef<{ [busId: string]: L.Marker }>({});
  const stopMarkersRef = useRef<{ [stopId: string]: L.Marker }>({});
  const baseRoutePolylineRef = useRef<L.Polyline | null>(null);
  const baseRouteCasingRef = useRef<L.Polyline | null>(null);
  const approachingGlowPolylineRef = useRef<L.Polyline | null>(null);
  const approachingLineRef = useRef<L.Polyline | null>(null);
  const demoIntervalRef = useRef<NodeJS.Timeout | null>(null);

  // Active Bus
  const selectedBus = useMemo(
    () => fleet.find((b) => b.id === selectedBusId) || fleet[0],
    [fleet, selectedBusId]
  );

  // Map stations to polyline indices for accurate sequential corridor tracking
  const stationPolyIndices = useMemo(() => {
    return CORRIDOR_STATIONS.map((st) => ({
      station: st,
      idx: findClosestPolylineIndex([st.lat, st.lon], ROUTE_POLYLINE)
    }));
  }, []);

  // Compute Next Approaching Stop along route loop
  const nextApproachingStopInfo = useMemo(() => {
    const busIdx = findClosestPolylineIndex([selectedBus.lat, selectedBus.lon], ROUTE_POLYLINE);

    // Find the next station ahead of bus along polyline
    let targetCandidate = stationPolyIndices[0];
    let minForwardDist = Infinity;

    for (const item of stationPolyIndices) {
      let forwardSteps = item.idx - busIdx;
      if (forwardSteps < 0) {
        forwardSteps += ROUTE_POLYLINE.length; // wrap around loop
      }
      // If at same vertex or very close (< 35m), advance to next
      const directDist = getDistanceMeters(selectedBus.lat, selectedBus.lon, item.station.lat, item.station.lon);
      if (directDist < 35) {
        forwardSteps += ROUTE_POLYLINE.length;
      }
      if (forwardSteps > 0 && forwardSteps < minForwardDist) {
        minForwardDist = forwardSteps;
        targetCandidate = item;
      }
    }

    const nextStation = targetCandidate.station;
    const distanceMeters = getDistanceMeters(selectedBus.lat, selectedBus.lon, nextStation.lat, nextStation.lon);
    const distanceKm = (distanceMeters / 1000).toFixed(2);
    const effectiveSpeed = Math.max(selectedBus.speed, 25);
    const etaMinutes = selectedBus.speed === 0 ? Math.max(1, Math.round((distanceMeters / 1000 / 25) * 60)) : Math.max(1, Math.round((distanceMeters / 1000 / effectiveSpeed) * 60));

    // Construct path vertices from bus into the next approaching stop
    const pathSegment: [number, number][] = [[selectedBus.lat, selectedBus.lon]];
    let curr = busIdx;
    const dest = targetCandidate.idx;
    let guard = 0;
    while (curr !== dest && guard < ROUTE_POLYLINE.length) {
      curr = (curr + 1) % ROUTE_POLYLINE.length;
      pathSegment.push(ROUTE_POLYLINE[curr]);
      guard++;
    }
    pathSegment.push([nextStation.lat, nextStation.lon]);

    return {
      station: nextStation,
      distanceMeters,
      distanceKm,
      etaMinutes,
      isStandby: selectedBus.speed === 0,
      pathSegment
    };
  }, [selectedBus, stationPolyIndices]);

  // Toggle Alert for a Stop
  const toggleStopAlert = useCallback((stopId: string) => {
    setAlertedStops((prev) => ({
      ...prev,
      [stopId]: !prev[stopId]
    }));
  }, []);

  // Cycle Active Buses
  const cycleFleet = useCallback(() => {
    const currIdx = fleet.findIndex((b) => b.id === selectedBusId);
    const nextIdx = (currIdx + 1) % fleet.length;
    const nextBus = fleet[nextIdx];
    setSelectedBusId(nextBus.id);
    if (mapRef.current) {
      mapRef.current.flyTo([nextBus.lat, nextBus.lon], 17, { animate: true, duration: 1.0 });
    }
  }, [fleet, selectedBusId]);

  // Recenter on MBSTU Campus
  const recenterCampus = useCallback(() => {
    if (mapRef.current) {
      mapRef.current.flyTo([24.2355, 89.8916], 17, { animate: true, duration: 1.2 });
    }
  }, []);

  // 1. Initialize Leaflet Map
  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) return;

    const map = L.map(mapContainerRef.current, {
      center: [24.2365, 89.8914],
      zoom: 17,
      zoomControl: false,
      attributionControl: true
    });

    // Google Maps Satellite Hybrid (lyrs=y)
    const googleSatLayer = L.tileLayer(
      "https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
      {
        subdomains: ["0", "1", "2", "3"],
        maxZoom: 21,
        attribution: "&copy; Google Maps"
      }
    );

    // Google Maps Standard Roadmap (lyrs=m)
    const googleStreetLayer = L.tileLayer(
      "https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}",
      {
        subdomains: ["0", "1", "2", "3"],
        maxZoom: 21,
        attribution: "&copy; Google Maps"
      }
    );

    satLayerRef.current = googleSatLayer;
    streetLayerRef.current = googleStreetLayer;

    if (satelliteView) {
      googleSatLayer.addTo(map);
    } else {
      googleStreetLayer.addTo(map);
    }

    // Zoom control at bottom-right (above FAB controls)
    L.control.zoom({ position: "bottomright" }).addTo(map);

    // Campus Standby Cluster Circle: Translucent sky-blue circle around Prottoy-71 ([24.23488, 89.89155])
    L.circle([24.23488, 89.89155], {
      radius: 58,
      color: "#38bdf8",
      weight: 1.5,
      fillColor: "#38bdf8",
      fillOpacity: 0.22,
      dashArray: "4, 6"
    }).addTo(map);

    // Base Route Polyline with Dark Casing
    // 1. Casing: dark #1e1b4b, weight: 8, opacity: 0.55
    baseRouteCasingRef.current = L.polyline(ROUTE_POLYLINE, {
      color: "#1e1b4b",
      weight: 8,
      opacity: 0.55,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    // 2. Base Periwinkle Blue: #818cf8, weight: 5, opacity: 0.95
    baseRoutePolylineRef.current = L.polyline(ROUTE_POLYLINE, {
      color: "#818cf8",
      weight: 5,
      opacity: 0.95,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    // Dynamic Approaching Road Highlights:
    // Glow Outer: #00f2fe, weight: 13, opacity: 0.42
    approachingGlowPolylineRef.current = L.polyline([], {
      color: "#00f2fe",
      weight: 13,
      opacity: 0.42,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    // Dash Flow: #00f2fe / #38bdf8, weight: 5, dashArray: 8, 6, animated class
    approachingLineRef.current = L.polyline([], {
      color: "#00f2fe",
      weight: 5,
      opacity: 1,
      dashArray: "8, 6",
      className: "animated-approaching-road",
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    mapRef.current = map;

    // Invalidate size on mount & resize
    setTimeout(() => map.invalidateSize(), 200);
    const ro = new ResizeObserver(() => map.invalidateSize());
    if (mapContainerRef.current) ro.observe(mapContainerRef.current);

    // User location
    if ("geolocation" in navigator) {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          const uLat = pos.coords.latitude;
          const uLon = pos.coords.longitude;
          setUserLocation({ lat: uLat, lon: uLon });
        },
        () => {},
        { enableHighAccuracy: true, timeout: 6000 }
      );
    }

    return () => {
      ro.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // 2. Toggle Satellite vs Street Layer
  useEffect(() => {
    if (!mapRef.current || !satLayerRef.current || !streetLayerRef.current) return;
    if (satelliteView) {
      if (mapRef.current.hasLayer(streetLayerRef.current)) {
        mapRef.current.removeLayer(streetLayerRef.current);
      }
      satLayerRef.current.addTo(mapRef.current);
    } else {
      if (mapRef.current.hasLayer(satLayerRef.current)) {
        mapRef.current.removeLayer(satLayerRef.current);
      }
      streetLayerRef.current.addTo(mapRef.current);
    }
  }, [satelliteView]);

  // 3. Render / Update All 28 Red Lollipop Bus Stop Sign Markers
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;
    const nextStopId = nextApproachingStopInfo.station.id;
    const etaText = nextApproachingStopInfo.isStandby
      ? `${nextApproachingStopInfo.distanceKm} km`
      : `${nextApproachingStopInfo.etaMinutes} min`;

    CORRIDOR_STATIONS.forEach((st) => {
      const isNext = st.id === nextStopId;
      const isAlerted = !!alertedStops[st.id];

      const html = `
        <div class="lollipop-stop-marker ${isNext ? "is-next" : ""} ${isAlerted ? "is-alerted" : ""}">
          ${isNext ? '<div class="radar-pulse-ring"></div>' : ""}
          ${isNext ? `<div class="next-floating-badge"><span class="badge-dot">●</span> NEXT • ${etaText}</div>` : ""}
          ${isAlerted ? '<div class="alert-indicator-badge">🔔</div>' : ""}
          <div class="lollipop-head">
            <span class="lollipop-text">BUS</span>
          </div>
          <div class="lollipop-stem"></div>
          <div class="lollipop-base"></div>
          <div class="lollipop-label">${st.name}</div>
        </div>
      `;

      const icon = L.divIcon({
        className: "lollipop-div-icon",
        html,
        iconSize: [80, 48],
        iconAnchor: [40, 26]
      });

      if (stopMarkersRef.current[st.id]) {
        stopMarkersRef.current[st.id].setIcon(icon);
      } else {
        const marker = L.marker([st.lat, st.lon], { icon, zIndexOffset: 100 }).addTo(map);
        marker.on("click", () => {
          toggleStopAlert(st.id);
        });
        stopMarkersRef.current[st.id] = marker;
      }
    });
  }, [nextApproachingStopInfo, alertedStops, toggleStopAlert]);

  // 4. Update Dynamic Approaching Road Highlight Segment
  useEffect(() => {
    if (!approachingLineRef.current || !approachingGlowPolylineRef.current) return;
    const path = nextApproachingStopInfo.pathSegment;
    approachingGlowPolylineRef.current.setLatLngs(path);
    approachingLineRef.current.setLatLngs(path);
  }, [nextApproachingStopInfo]);

  // 5. Render / Update Teardrop Bus Pins
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;

    fleet.forEach((bus) => {
      const isSelected = bus.id === selectedBusId;
      const pinColor = isSelected ? "#38bdf8" : bus.color || "#818cf8";
      const size = isSelected ? 34 : 29;
      const svgSize = isSelected ? 18 : 15;

      const html = `
        <div class="teardrop-bus-pin ${isSelected ? "selected" : ""}">
          ${
            isSelected
              ? `
            <div class="bus-floating-info-pill" style="border-color:${bus.color}">
              <span class="bus-pill-name">${bus.nameBn}</span>
              <span class="bus-pill-speed ${bus.speed > 0 ? "moving" : ""}">${bus.speed > 0 ? `${bus.speed} km/h` : "Standby"}</span>
            </div>
          `
              : ""
          }
          <div class="teardrop-circle" style="border-color:${pinColor}; width:${size}px; height:${size}px;">
            <svg class="bus-svg-icon" viewBox="0 0 24 24" width="${svgSize}" height="${svgSize}">
              <path fill="#ef4444" d="M4 16c0 .88.39 1.67 1 2.22V20c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h8v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4s-8 .5-8 4v10zm3.5 1c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm9 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm1.5-6H6V6h12v5z"/>
            </svg>
          </div>
          <div class="teardrop-tail" style="border-top-color:${pinColor};"></div>
        </div>
      `;

      const icon = L.divIcon({
        className: "teardrop-div-icon",
        html,
        iconSize: [size, size + 8],
        iconAnchor: [size / 2, size + 5]
      });

      if (busMarkersRef.current[bus.id]) {
        busMarkersRef.current[bus.id].setLatLng([bus.lat, bus.lon]);
        busMarkersRef.current[bus.id].setIcon(icon);
        busMarkersRef.current[bus.id].setZIndexOffset(isSelected ? 500 : 200);
      } else {
        const marker = L.marker([bus.lat, bus.lon], { icon, zIndexOffset: isSelected ? 500 : 200 }).addTo(map);
        marker.on("click", () => {
          setSelectedBusId(bus.id);
        });
        busMarkersRef.current[bus.id] = marker;
      }
    });
  }, [fleet, selectedBusId]);

  // 6. Smooth Motion Simulation (When isDemoMode === true)
  useEffect(() => {
    if (!isDemoMode) {
      if (demoIntervalRef.current) {
        clearInterval(demoIntervalRef.current);
        demoIntervalRef.current = null;
      }
      return;
    }

    let progressIdx = 0;
    demoIntervalRef.current = setInterval(() => {
      progressIdx = (progressIdx + 1) % ROUTE_POLYLINE.length;
      const nextCoord = ROUTE_POLYLINE[progressIdx];
      const prevCoord = ROUTE_POLYLINE[(progressIdx - 1 + ROUTE_POLYLINE.length) % ROUTE_POLYLINE.length];

      // Calculate approximate heading
      const dLat = nextCoord[0] - prevCoord[0];
      const dLon = nextCoord[1] - prevCoord[1];
      const headingDeg = Math.round((Math.atan2(dLon, dLat) * 180) / Math.PI + 360) % 360;

      setFleet((prev) =>
        prev.map((b) => {
          if (b.id === selectedBusId) {
            return {
              ...b,
              lat: nextCoord[0],
              lon: nextCoord[1],
              speed: 28,
              heading: headingDeg,
              isDriving: true,
              status: "En Route along Corridor"
            };
          }
          return b;
        })
      );
    }, 1200);

    return () => {
      if (demoIntervalRef.current) {
        clearInterval(demoIntervalRef.current);
        demoIntervalRef.current = null;
      }
    };
  }, [isDemoMode, selectedBusId]);

  // 7. Real MBSTU Socket.IO v4 WebSocket Stream (When isDemoMode === false)
  useEffect(() => {
    if (isDemoMode) return;

    let ws: WebSocket | null = null;
    let heartbeatTimer: NodeJS.Timeout | null = null;
    let isDisposed = false;

    const connect = () => {
      try {
        ws = new WebSocket("wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket");

        ws.onopen = () => {
          // Socket connected
        };

        ws.onmessage = (event) => {
          if (isDisposed) return;
          const raw = String(event.data);

          // 1. Engine.IO Hello -> Send Socket.IO 40 connect
          if (raw.startsWith("0")) {
            ws?.send("40");
            setIsLiveWs(true);

            heartbeatTimer = setInterval(() => {
              if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send("2"); // Ping every 22s
              }
            }, 22000);
          }
          // 2. Server Ping/Pong
          else if (raw === "2") {
            ws?.send("3");
          }
          // 3. Telemetry Event Packets
          else if (raw.startsWith("42")) {
            try {
              const parsed = JSON.parse(raw.slice(2));
              if (!Array.isArray(parsed) || parsed.length < 2) return;
              const evt = parsed[0];
              const payload = parsed[1];

              if (evt === "flutter-bus-data" || evt === "studentGpsData") {
                const busId = payload.id || payload.busId;
                const busName = payload.busName;
                const lat = Number(payload.lat);
                const lon = Number(payload.lon || payload.long);
                const speed = Number(payload.speed ?? 0);
                const heading = Number(payload.heading ?? 0);
                const isDriving = payload.isDriving ?? speed > 0;
                const time = payload.time || new Date().toLocaleTimeString();

                if (!busId || isNaN(lat) || isNaN(lon)) return;

                setFleet((prevFleet) =>
                  prevFleet.map((b) => {
                    if (b.id === busId || b.nameBn === busName) {
                      return {
                        ...b,
                        lat,
                        lon,
                        speed,
                        heading,
                        isDriving,
                        status: speed > 0 ? "In Transit along Corridor" : "Parked at Campus Standby",
                        lastUpdated: time
                      };
                    }
                    return b;
                  })
                );
              }
            } catch (err) {}
          }
        };

        ws.onclose = () => {
          if (!isDisposed) {
            setIsLiveWs(false);
            setTimeout(connect, 4000);
          }
        };

        ws.onerror = () => {
          if (!isDisposed) {
            setIsLiveWs(false);
          }
        };
      } catch (err) {
        setIsLiveWs(false);
      }
    };

    connect();

    return () => {
      isDisposed = true;
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (ws) ws.close();
    };
  }, [isDemoMode]);

  return (
    <div className="transit-container" style={{ "--bus-accent-color": selectedBus.color } as any}>
      {/* ===================================================================
          Map Canvas (80%+ Viewport)
          =================================================================== */}
      <div className="transit-map-wrapper">
        <div ref={mapContainerRef} className="transit-map-canvas" />
      </div>

      {/* ===================================================================
          A. Top Floating Overlay (z-20)
          =================================================================== */}
      <div className="transit-top-overlay">
        {/* Header Glassmorphic Pill */}
        <div className="transit-header-pill">
          <div className="transit-header-left">
            <div
              className="transit-header-icon-box"
              style={{ backgroundColor: `${selectedBus.color}25`, color: selectedBus.color, border: `1px solid ${selectedBus.color}60` }}
            >
              <Bus size={20} />
            </div>

            <div className="transit-header-titles">
              <div className="transit-header-title-row">
                <span className="transit-header-main-title">MBSTU Bus Tracker</span>
                <span className={`transit-status-pill ${isDemoMode ? "demo" : isLiveWs ? "live" : "offline"}`}>
                  <span className="transit-pulse-dot" style={{ background: isDemoMode ? "#38bdf8" : isLiveWs ? "#34d399" : "#fbbf24" }} />
                  {isDemoMode ? "Motion ON" : isLiveWs ? "Live Connected" : "Connecting..."}
                </span>
              </div>
              <span className="transit-header-sub">Santosh Campus ⇄ Nirala (City Center) Loop</span>
            </div>
          </div>

          {/* 1-Tap Telemetry Mode Toggle */}
          <button
            onClick={() => setIsDemoMode((prev) => !prev)}
            className="transit-mode-toggle-btn"
            title="Toggle between Live WebSocket and Road Motion Simulation"
          >
            {isDemoMode ? <Radio size={14} color="#38bdf8" /> : <Play size={14} color="#34d399" />}
            <span>{isDemoMode ? "Real WS" : "Motion ON"}</span>
          </button>
        </div>

        {/* Horizontal Fleet Selector Chips */}
        <div className="transit-chips-scroll">
          {fleet.map((bus) => {
            const isSelected = bus.id === selectedBusId;
            return (
              <button
                key={bus.id}
                onClick={() => {
                  setSelectedBusId(bus.id);
                  if (mapRef.current) {
                    mapRef.current.flyTo([bus.lat, bus.lon], 17, { animate: true, duration: 0.8 });
                  }
                }}
                className={`transit-bus-chip ${isSelected ? "selected" : ""}`}
                style={isSelected ? { borderColor: bus.color, boxShadow: `0 0 16px ${bus.color}60` } : {}}
              >
                <span className="transit-chip-dot" style={{ backgroundColor: bus.color, color: bus.color }} />
                <span>{bus.nameBn}</span>
                <span style={{ fontSize: 10, opacity: 0.7, fontFamily: "monospace" }}>{bus.id}</span>
                <span className={`transit-chip-speed ${bus.speed > 0 ? "moving" : ""}`}>
                  {bus.speed > 0 ? `${bus.speed}k` : "Standby"}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* ===================================================================
          B. Right-Side Floating Map Controls (z-20)
          =================================================================== */}
      <div className={`transit-fab-controls ${sheetExpanded ? "sheet-expanded" : ""}`}>
        {/* Active Fleet Cycle Button */}
        <button
          onClick={cycleFleet}
          className="transit-fab-btn"
          title="Cycle through active buses"
        >
          <Bus size={18} color="#38bdf8" />
          <span className="transit-fab-badge">{fleet.length}</span>
        </button>

        {/* Map Layer Toggle Button (Satellite Hybrid vs Street) */}
        <button
          onClick={() => setSatelliteView((prev) => !prev)}
          className="transit-fab-btn"
          title="Toggle Google Satellite / Street Roadmap"
        >
          <Layers size={18} color={satelliteView ? "#38bdf8" : "#94a3b8"} />
        </button>

        {/* Campus Recenter Button */}
        <button
          onClick={recenterCampus}
          className="transit-fab-btn"
          title="Fly to MBSTU Santosh Campus"
        >
          <MapPin size={18} color="#38bdf8" />
        </button>
      </div>

      {/* ===================================================================
          C. Material 3 Peek & Expand Bottom Sheet (z-30)
          =================================================================== */}
      <div className={`transit-bottom-sheet ${sheetExpanded ? "expanded" : "collapsed"}`}>
        {/* Drag Handle */}
        <button
          className="transit-sheet-drag-handle"
          onClick={() => setSheetExpanded((prev) => !prev)}
          aria-label="Toggle sheet height"
        >
          <div className="transit-drag-bar" />
        </button>

        {/* Sheet Content Scroll Body */}
        <div className="transit-sheet-body">
          {/* Selected Bus Header */}
          <div className="transit-bus-header-card">
            <div className="transit-bus-header-identity">
              <div
                className="transit-bus-avatar"
                style={{ backgroundColor: selectedBus.color }}
              >
                <span>{selectedBus.id.replace("SB", "")}</span>
              </div>
              <div>
                <div className="transit-bus-name-title">
                  {selectedBus.nameBn} ({selectedBus.nameEn})
                </div>
                <div className="transit-bus-route-subtitle">{selectedBus.routeName}</div>
              </div>
            </div>

            <div className="transit-bus-telemetry-right">
              <span className={`transit-speed-pill ${selectedBus.speed > 0 ? "moving" : "stopped"}`}>
                <Compass size={12} />
                {selectedBus.speed > 0 ? `${selectedBus.speed} km/h` : "Standby"}
              </span>
              <span className="transit-gps-coords-text">
                {selectedBus.lat.toFixed(4)}°N, {selectedBus.lon.toFixed(4)}°E
              </span>
            </div>
          </div>

          {/* Prominent "Next Approaching Stop" Card */}
          <div className="transit-next-stop-card">
            <div className="transit-next-left">
              <div className="transit-next-pin-box">
                <Navigation size={18} />
              </div>
              <div className="transit-next-info">
                <span className="transit-next-banner-tag">
                  <Sparkles size={11} /> NEXT APPROACHING STOP ➔
                </span>
                <span className="transit-next-stop-en">{nextApproachingStopInfo.station.name}</span>
                <span className="transit-next-stop-bn">{nextApproachingStopInfo.station.nameBn}</span>
                <span className="transit-next-dist-pill">
                  {nextApproachingStopInfo.distanceKm} km ahead along road
                </span>
              </div>
            </div>

            <div className="transit-next-right">
              <div className="transit-next-eta-container">
                <span className="transit-next-eta-num">
                  {nextApproachingStopInfo.isStandby
                    ? "Standby"
                    : `~${nextApproachingStopInfo.etaMinutes} min`}
                </span>
                <span className="transit-next-eta-lbl">
                  {nextApproachingStopInfo.isStandby
                    ? `~${nextApproachingStopInfo.etaMinutes} min drive`
                    : "Estimated Arrival"}
                </span>
              </div>

              {/* 1-Tap Bell Alert Button */}
              <button
                onClick={() => toggleStopAlert(nextApproachingStopInfo.station.id)}
                className={`transit-bell-alert-btn ${alertedStops[nextApproachingStopInfo.station.id] ? "armed" : ""}`}
                title="Notify me when bus approaches this stop"
              >
                <Bell size={18} />
              </button>
            </div>
          </div>

          {/* Footer Row */}
          <div className="transit-sheet-footer-row">
            <div className="transit-sheet-status-text">
              <span style={{ color: "#38bdf8" }}>●</span>
              <span>{selectedBus.status}</span>
            </div>

            <button
              onClick={() => setSheetExpanded((prev) => !prev)}
              className="transit-expand-toggle-btn"
            >
              <span>{sheetExpanded ? "Hide Stops ⌄" : "All 28 Route Stops ⌃"}</span>
              {sheetExpanded ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
            </button>
          </div>

          {/* Expanded State: 28-Stoppage Corridor Stepper */}
          {sheetExpanded && (
            <div className="transit-corridor-stepper-section">
              <div className="transit-stepper-header">
                <span>MBSTU Corridor Stoppages (28 Total)</span>
                <span style={{ fontSize: 9.5, color: "#64748b" }}>Tap 🔔 to set arrival alert</span>
              </div>

              <div className="transit-stepper-list">
                {CORRIDOR_STATIONS.map((st, idx) => {
                  const isApproaching = st.id === nextApproachingStopInfo.station.id;
                  const isAlerted = !!alertedStops[st.id];
                  const distM = getDistanceMeters(selectedBus.lat, selectedBus.lon, st.lat, st.lon);
                  const distKm = (distM / 1000).toFixed(2);
                  const etaM = Math.max(1, Math.round((distM / 1000 / Math.max(selectedBus.speed, 25)) * 60));

                  return (
                    <div
                      key={st.id}
                      onClick={() => {
                        if (mapRef.current) {
                          mapRef.current.flyTo([st.lat, st.lon], 18, { animate: true, duration: 0.8 });
                        }
                      }}
                      className={`transit-stepper-item ${isApproaching ? "is-approaching" : ""} ${st.isCampus ? "is-campus" : ""}`}
                    >
                      {idx < CORRIDOR_STATIONS.length - 1 && <div className="transit-stepper-line" />}
                      <div className="transit-stepper-node">{st.order}</div>

                      <div className="transit-stepper-content">
                        <div className="transit-stepper-title-row">
                          <span className="transit-stepper-stop-name">{st.name}</span>
                          <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                            {isApproaching && <span className="transit-stepper-badge-next">NEXT</span>}
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                toggleStopAlert(st.id);
                              }}
                              style={{
                                background: "transparent",
                                border: "none",
                                cursor: "pointer",
                                color: isAlerted ? "#38bdf8" : "#64748b",
                                padding: 2
                              }}
                              title="Toggle stop arrival alert"
                            >
                              <Bell size={14} />
                            </button>
                          </div>
                        </div>

                        <div className="transit-stepper-sub">
                          <span>{st.nameBn}</span>
                          <span>•</span>
                          <span>{distKm} km</span>
                          <span>•</span>
                          <span style={{ color: isApproaching ? "#38bdf8" : "#94a3b8" }}>
                            ~{etaM} min {selectedBus.speed === 0 ? "drive" : "ETA"}
                          </span>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

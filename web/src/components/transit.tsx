"use client";

import { useEffect, useState, useRef, useMemo, useCallback } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "./transit.css";
import { REAL_STREET_POLYLINE } from "./transit-route-data";
import {
  Bus,
  Layers,
  MapPin,
  Compass,
  Radio,
  Bell,
  ChevronUp,
  ChevronDown,
  Navigation,
  Play,
  Sparkles,
  LocateFixed,
  CheckCircle2,
  X
} from "lucide-react";

/* ==========================================================================
   Types & Verified MBSTU Data
   ========================================================================== */

export interface MbstuBusData {
  id: string;
  nameBn: string;
  nameEn: string;
  lat: number;
  lon: number;
  speed: number;
  heading: number;
  hasDirection?: boolean;
  routeName: string;
  status: string;
  color: string;
  isDriving: boolean;
  lastUpdated?: string;
  receivedAt?: number;
}

type DisplayBusData = MbstuBusData & {
  positionAgeSeconds: number | null;
  isEstimated: boolean;
  isStale: boolean;
};

const MAX_PREDICTION_SECONDS = 15;

export interface CorridorStation {
  id: string;
  name: string;
  nameBn: string;
  lat: number;
  lon: number;
  order: number;
  isCampus: boolean;
}

// Known MBSTU Bus Metadata
const BUS_METADATA: { [id: string]: { nameEn: string; color: string; routeName: string } } = {
  SB0001: { nameEn: "Gitanjali", color: "#6366f1", routeName: "MBSTU Campus Standby" },
  SB0002: { nameEn: "Agnibeena", color: "#0284c7", routeName: "Campus ➔ Nirala (City Center)" },
  SB0003: { nameEn: "Sonartori", color: "#0d9488", routeName: "Campus ➔ Tangail City Loop" },
  SB0009: { nameEn: "Banolata", color: "#059669", routeName: "Campus ➔ Kagmari ➔ Nirala" },
  SB0010: { nameEn: "Raktakarabi", color: "#e11d48", routeName: "MBSTU Campus Standby" }
};

// Verified Corridor Stoppages along the MBSTU - Tangail City Street Route (28 Stops)
export const CORRIDOR_STATIONS: CorridorStation[] = [
  { id: "mukto_mancho", name: "Mukto Mancho Bus Stop", nameBn: "মুক্তমঞ্চ বাস স্টপ", lat: 24.23512, lon: 89.89025, order: 1, isCampus: true },
  { id: "ab1", name: "Academic Building-1 Bus Stop", nameBn: "একাডেমিক ভবন-১ বাস স্টপ", lat: 24.23715, lon: 89.89038, order: 2, isCampus: true },
  { id: "soh_hall", name: "Bangabandhu / SOH Hall Bus Stop", nameBn: "বঙ্গবন্ধু / এসওএইচ হল বাস স্টপ", lat: 24.23835, lon: 89.89042, order: 3, isCampus: true },
  { id: "mannan_hall", name: "Mannan Hall Bus Stop", nameBn: "মান্নান হল বাস স্টপ", lat: 24.23855, lon: 89.89105, order: 4, isCampus: true },
  { id: "ab2", name: "Academic Building-2 Bus Stop", nameBn: "একাডেমিক ভবন-২ বাস স্টপ", lat: 24.23712, lon: 89.89152, order: 5, isCampus: true },
  { id: "fifth_gate", name: "Fifth Gate Bus Stop", nameBn: "পঞ্চম গেট বাস স্টপ", lat: 24.23578, lon: 89.89178, order: 6, isCampus: true },
  { id: "prottoy_71", name: "Prottoy-71 Bus Stop", nameBn: "প্রত্যয়-৭১ বাস স্টপ", lat: 24.23482, lon: 89.89195, order: 7, isCampus: true },
  { id: "first_gate", name: "First Gate (Main Gate) Bus Stop", nameBn: "প্রথম গেট (প্রধান ফটক) বাস স্টপ", lat: 24.23363, lon: 89.89218, order: 8, isCampus: true },
  { id: "arif_nagar", name: "Arif Nagar Bus Stop", nameBn: "আরিফ নগর বাস স্টপ", lat: 24.23398, lon: 89.89635, order: 9, isCampus: false },
  { id: "shena_nagar", name: "Shena Nagar Bus Stop", nameBn: "সেনা নগর বাস স্টপ", lat: 24.23404, lon: 89.89991, order: 10, isCampus: false },
  { id: "kagmari_more", name: "Kagmari More Bus Stop", nameBn: "কাগমারী মোড় বাস স্টপ", lat: 24.23488, lon: 89.90125, order: 11, isCampus: false },
  { id: "kagmari_bridge_1", name: "Kagmari Bridge-1 Bus Stop", nameBn: "কাগমারী ব্রিজ-১ বাস স্টপ", lat: 24.23800, lon: 89.90250, order: 12, isCampus: false },
  { id: "kagmari_bridge_2", name: "Kagmari Bridge-2 Bus Stop", nameBn: "কাগমারী ব্রিজ-২ বাস স্টপ", lat: 24.24155, lon: 89.90426, order: 13, isCampus: false },
  { id: "baby_stand", name: "Baby Stand Bus Stop", nameBn: "বেবি স্ট্যান্ড বাস স্টপ", lat: 24.24415, lon: 89.90445, order: 14, isCampus: false },
  { id: "shantikunja", name: "Shantikunja More Bus Stop", nameBn: "শান্তিকুঞ্জ মোড় বাস স্টপ", lat: 24.24665, lon: 89.90735, order: 15, isCampus: false },
  { id: "nirala_more", name: "Nirala More Bus Stop", nameBn: "নিরালা মোড় বাস স্টপ", lat: 24.24985, lon: 89.91485, order: 16, isCampus: false },
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

function getBearingDegrees(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const toRad = (degrees: number) => (degrees * Math.PI) / 180;
  const toDegrees = (radians: number) => (radians * 180) / Math.PI;
  const y = Math.sin(toRad(lon2 - lon1)) * Math.cos(toRad(lat2));
  const x =
    Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) -
    Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(toRad(lon2 - lon1));
  return (toDegrees(Math.atan2(y, x)) + 360) % 360;
}

function parseTelemetryTimestamp(value: unknown, receivedAt: number): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    const timestamp = value < 1_000_000_000_000 ? value * 1000 : value;
    return timestamp <= receivedAt + 30_000 ? Math.min(timestamp, receivedAt) : receivedAt;
  }
  if (typeof value !== "string" || !value.trim()) return receivedAt;

  const raw = value.trim();
  const hasTimezone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(raw);
  let timestamp = Number.NaN;
  if (hasTimezone) {
    timestamp = Date.parse(raw);
  } else {
    const parts = raw.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM)?$/i);
    if (parts) {
      const [, year, month, day, hour, minute, second = "0", meridiem] = parts;
      let hourOfDay = Number(hour);
      if (meridiem?.toUpperCase() === "PM" && hourOfDay < 12) hourOfDay += 12;
      if (meridiem?.toUpperCase() === "AM" && hourOfDay === 12) hourOfDay = 0;
      timestamp = new Date(Number(year), Number(month) - 1, Number(day), hourOfDay, Number(minute), Number(second)).getTime();
    } else {
      timestamp = Date.parse(raw);
    }
  }
  return Number.isFinite(timestamp) && timestamp <= receivedAt + 30_000 ? Math.min(timestamp, receivedAt) : receivedAt;
}

function predictBusPosition(bus: MbstuBusData, now: number): DisplayBusData {
  if (!bus.receivedAt) {
    return { ...bus, positionAgeSeconds: null, isEstimated: false, isStale: true };
  }

  const ageSeconds = Math.max(0, (now - bus.receivedAt) / 1000);
  const projectedSeconds = Math.min(ageSeconds, MAX_PREDICTION_SECONDS);
  const distanceMeters = bus.isDriving && bus.speed > 0 && bus.speed <= 100 && bus.hasDirection !== false
    ? (bus.speed * 1000 * projectedSeconds) / 3600
    : 0;
  let lat = bus.lat;
  let lon = bus.lon;

  if (distanceMeters > 0) {
    const earthRadius = 6371000;
    const bearing = (bus.heading * Math.PI) / 180;
    const angularDistance = distanceMeters / earthRadius;
    const lat1 = (bus.lat * Math.PI) / 180;
    const lon1 = (bus.lon * Math.PI) / 180;
    const lat2 = Math.asin(
      Math.sin(lat1) * Math.cos(angularDistance) +
        Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearing)
    );
    const lon2 =
      lon1 +
      Math.atan2(
        Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat1),
        Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2)
      );
    lat = (lat2 * 180) / Math.PI;
    lon = (((lon2 * 180) / Math.PI + 540) % 360) - 180;
  }

  return {
    ...bus,
    lat,
    lon,
    positionAgeSeconds: ageSeconds,
    isEstimated: ageSeconds > 1,
    isStale: ageSeconds > MAX_PREDICTION_SECONDS
  };
}

// Find closest vertex on the real street polyline
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

// Clean badge label for bus avatar
function formatBusShortId(id: string): string {
  if (id.startsWith("SB00")) return "SB" + id.slice(4);
  if (id.startsWith("SB0")) return "SB" + id.slice(3);
  return id;
}

function formatStopTitle(name: string): string {
  return name.replace(/\s+Bus Stop$/i, "").replace(/\s+Bus Stand$/i, "");
}

/* ==========================================================================
   Component
   ========================================================================== */

export function Transit({ ctx }: { ctx?: any }) {
  const [fleet, setFleet] = useState<MbstuBusData[]>([]);
  const [selectedBusId, setSelectedBusId] = useState<string>("");
  const [satelliteView, setSatelliteView] = useState<boolean>(true);
  const [sheetExpanded, setSheetExpanded] = useState<boolean>(false);
  const [isDemoMode, setIsDemoMode] = useState<boolean>(false);
  const [wsStatus, setWsStatus] = useState<"connecting" | "connected" | "disconnected">("connecting");
  const [alertedStops, setAlertedStops] = useState<{ [stopId: string]: boolean }>({});
  const [arrivalNotice, setArrivalNotice] = useState<{ busName: string; stopName: string } | null>(null);
  const [clockNow, setClockNow] = useState(() => Date.now());

  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const satLayerRef = useRef<L.TileLayer | null>(null);
  const streetLayerRef = useRef<L.TileLayer | null>(null);
  const busMarkersRef = useRef<{ [busId: string]: L.Marker }>({});
  const busMarkerSignaturesRef = useRef<{ [busId: string]: string }>({});
  const stopMarkersRef = useRef<{ [stopId: string]: L.Marker }>({});
  const approachingGlowPolylineRef = useRef<L.Polyline | null>(null);
  const approachingLineRef = useRef<L.Polyline | null>(null);
  const demoIntervalRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    const timer = setInterval(() => setClockNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, []);

  // Bottom Sheet Swipe Gestures
  const touchStartY = useRef<number | null>(null);
  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartY.current = e.touches[0].clientY;
  };
  const handleTouchEnd = (e: React.TouchEvent) => {
    if (touchStartY.current === null) return;
    const diff = touchStartY.current - e.changedTouches[0].clientY;
    // diff > 40 means swiped up -> expand
    if (diff > 40 && !sheetExpanded) {
      setSheetExpanded(true);
    } else if (diff < -40 && sheetExpanded) {
      // diff < -40 means swiped down -> collapse
      setSheetExpanded(false);
    }
    touchStartY.current = null;
  };

  // Load verified fleet from localStorage on mount if available
  useEffect(() => {
    try {
      const cached = localStorage.getItem("classmate_mbstu_fleet");
      if (cached) {
        const parsed = JSON.parse(cached);
        if (Array.isArray(parsed) && parsed.length > 0) {
          setFleet(parsed);
          setSelectedBusId(parsed[0].id);
        }
      }
    } catch (e) {}
  }, []);

  // Save fleet updates to cache
  useEffect(() => {
    if (fleet.length > 0) {
      try {
        localStorage.setItem("classmate_mbstu_fleet", JSON.stringify(fleet));
      } catch (e) {}
    }
  }, [fleet]);

  const displayFleet = useMemo(
    () => fleet.map((bus) => predictBusPosition(bus, clockNow)),
    [fleet, clockNow]
  );
  const freshFleetCount = displayFleet.filter((bus) => bus.positionAgeSeconds !== null && !bus.isStale).length;

  // Active Bus
  const selectedBus = useMemo(
    () => displayFleet.find((b) => b.id === selectedBusId) || (displayFleet.length > 0 ? displayFleet[0] : null),
    [displayFleet, selectedBusId]
  );

  // Map stations to polyline indices for accurate sequential corridor tracking
  const stationPolyIndices = useMemo(() => {
    return CORRIDOR_STATIONS.map((st) => ({
      station: st,
      idx: findClosestPolylineIndex([st.lat, st.lon], REAL_STREET_POLYLINE)
    }));
  }, []);

  // Compute Next Approaching Stop along real street route loop
  const nextApproachingStopInfo = useMemo(() => {
    if (!selectedBus) return null;

    const busIdx = findClosestPolylineIndex([selectedBus.lat, selectedBus.lon], REAL_STREET_POLYLINE);

    let targetCandidate = stationPolyIndices[0];
    let minForwardSteps = Infinity;

    for (const item of stationPolyIndices) {
      let forwardSteps = item.idx - busIdx;
      if (forwardSteps < 0) {
        forwardSteps += REAL_STREET_POLYLINE.length;
      }
      const directDist = getDistanceMeters(selectedBus.lat, selectedBus.lon, item.station.lat, item.station.lon);
      if (directDist < 40) {
        forwardSteps += REAL_STREET_POLYLINE.length;
      }
      if (forwardSteps > 0 && forwardSteps < minForwardSteps) {
        minForwardSteps = forwardSteps;
        targetCandidate = item;
      }
    }

    const nextStation = targetCandidate.station;

    // Construct path segment along vertices from bus position into the approaching stop
    const pathSegment: [number, number][] = [[selectedBus.lat, selectedBus.lon]];
    let curr = busIdx;
    const dest = targetCandidate.idx;
    let guard = 0;
    while (curr !== dest && guard < REAL_STREET_POLYLINE.length) {
      curr = (curr + 1) % REAL_STREET_POLYLINE.length;
      pathSegment.push(REAL_STREET_POLYLINE[curr]);
      guard++;
    }
    pathSegment.push([nextStation.lat, nextStation.lon]);

    const distanceMeters = pathSegment.slice(1).reduce((total, point, index) => {
      const previous = pathSegment[index];
      return total + getDistanceMeters(previous[0], previous[1], point[0], point[1]);
    }, 0);
    const distanceKm = (distanceMeters / 1000).toFixed(2);
    const isStandby = selectedBus.speed === 0;
    const effectiveSpeed = Math.max(selectedBus.speed, 25);
    const etaMinutes = Math.max(1, Math.round((distanceMeters / 1000 / effectiveSpeed) * 60));

    return {
      station: nextStation,
      distanceMeters,
      distanceKm,
      etaMinutes,
      isStandby,
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

  useEffect(() => {
    if (!selectedBus || selectedBus.isStale) return;
    const reachedStop = CORRIDOR_STATIONS.find(
      (station) =>
        alertedStops[station.id] &&
        getDistanceMeters(selectedBus.lat, selectedBus.lon, station.lat, station.lon) <= 200
    );
    if (!reachedStop) return;
    setAlertedStops((prev) => ({ ...prev, [reachedStop.id]: false }));
    setArrivalNotice({ busName: selectedBus.nameBn, stopName: reachedStop.name });
  }, [selectedBus, alertedStops]);

  const centerSelectedBus = useCallback(() => {
    if (selectedBus && mapRef.current) {
      mapRef.current.flyTo([selectedBus.lat, selectedBus.lon], 17, { animate: true, duration: 0.8 });
    }
  }, [selectedBus]);

  // Recenter Campus
  const recenterCampus = useCallback(() => {
    if (mapRef.current) {
      mapRef.current.flyTo([24.2355, 89.8916], 17, { animate: true, duration: 1.2 });
    }
  }, []);

  // 1. Initialize Leaflet Map with Real Street Polyline
  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) return;

    const map = L.map(mapContainerRef.current, {
      center: [24.2365, 89.8914],
      zoom: 17,
      zoomControl: false,
      attributionControl: true
    });

    const googleSatLayer = L.tileLayer(
      "https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
      {
        subdomains: ["0", "1", "2", "3"],
        maxZoom: 21,
        attribution: "&copy; Google Maps"
      }
    );

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

    L.control.zoom({ position: "bottomright" }).addTo(map);

    // Campus Standby Cluster Circle around Prottoy-71
    L.circle([24.23488, 89.89155], {
      radius: 58,
      color: "#38bdf8",
      weight: 1.5,
      fillColor: "#38bdf8",
      fillOpacity: 0.22,
      dashArray: "4, 6"
    }).addTo(map);

    // Accurate Real Street Polyline (428 vertices following real roads matching verified route)
    L.polyline(REAL_STREET_POLYLINE, {
      color: "#064e3b",
      weight: 8,
      opacity: 0.6,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    L.polyline(REAL_STREET_POLYLINE, {
      color: "#22c55e",
      weight: 5,
      opacity: 0.95,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    // Dynamic Approaching Road Segment Highlights
    approachingGlowPolylineRef.current = L.polyline([], {
      color: "#00f2fe",
      weight: 13,
      opacity: 0.42,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

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

    setTimeout(() => map.invalidateSize(), 200);
    const ro = new ResizeObserver(() => map.invalidateSize());
    if (mapContainerRef.current) ro.observe(mapContainerRef.current);

    return () => {
      ro.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // 2. Toggle Map Layer
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

  // 3. Render / Update All Red Lollipop Bus Stop Sign Markers
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;
    const nextStopId = nextApproachingStopInfo?.station.id;
    const etaText = nextApproachingStopInfo?.isStandby
      ? `${nextApproachingStopInfo.distanceKm} km`
      : `${nextApproachingStopInfo?.etaMinutes ?? 1} min`;

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
        const marker = L.marker([st.lat, st.lon], { icon, zIndexOffset: 100, title: st.name })
          .bindTooltip(st.name, { direction: "top", offset: [0, -12], opacity: 0.96, className: "transit-stop-tooltip" })
          .addTo(map);
        stopMarkersRef.current[st.id] = marker;
      }
    });
  }, [nextApproachingStopInfo, alertedStops, toggleStopAlert]);

  // 4. Update Dynamic Approaching Road Highlight
  useEffect(() => {
    if (!approachingLineRef.current || !approachingGlowPolylineRef.current) return;
    if (nextApproachingStopInfo && nextApproachingStopInfo.pathSegment.length > 0) {
      const path = nextApproachingStopInfo.pathSegment;
      approachingGlowPolylineRef.current.setLatLngs(path);
      approachingLineRef.current.setLatLngs(path);
    } else {
      approachingGlowPolylineRef.current.setLatLngs([]);
      approachingLineRef.current.setLatLngs([]);
    }
  }, [nextApproachingStopInfo]);

  // 5. Render / Update Teardrop Bus Pins (ONLY for discovered, active buses)
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;

    const activeIds = new Set(displayFleet.map((b) => b.id));
    Object.keys(busMarkersRef.current).forEach((id) => {
      if (!activeIds.has(id)) {
        busMarkersRef.current[id].remove();
        delete busMarkersRef.current[id];
        delete busMarkerSignaturesRef.current[id];
      }
    });

    displayFleet.forEach((bus) => {
      const isSelected = bus.id === selectedBusId;
      const pinColor = isSelected ? "#38bdf8" : bus.color || "#818cf8";
      const size = isSelected ? 34 : 29;
      const svgSize = isSelected ? 18 : 15;

      const html = `
        <div class="teardrop-bus-pin ${isSelected ? "selected" : ""} ${bus.isEstimated ? "estimated" : ""} ${bus.isStale ? "stale" : ""}">
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
      const iconSignature = [bus.nameBn, bus.color, bus.speed, isSelected, bus.isEstimated, bus.isStale].join("|");

      if (busMarkersRef.current[bus.id]) {
        busMarkersRef.current[bus.id].setLatLng([bus.lat, bus.lon]);
        if (busMarkerSignaturesRef.current[bus.id] !== iconSignature) {
          busMarkersRef.current[bus.id].setIcon(icon);
          busMarkerSignaturesRef.current[bus.id] = iconSignature;
        }
        busMarkersRef.current[bus.id].setZIndexOffset(isSelected ? 500 : 200);
        busMarkersRef.current[bus.id].options.title = bus.isStale
          ? "Bus location is stale"
          : bus.isEstimated
          ? "Estimated from the last GPS update"
          : "Live GPS location";
      } else {
        const marker = L.marker([bus.lat, bus.lon], {
          icon,
          zIndexOffset: isSelected ? 500 : 200,
          title: bus.isStale ? "Bus location is stale" : bus.isEstimated ? "Estimated from the last GPS update" : "Live GPS location"
        }).addTo(map);
        marker.on("click", () => {
          setSelectedBusId(bus.id);
        });
        busMarkersRef.current[bus.id] = marker;
        busMarkerSignaturesRef.current[bus.id] = iconSignature;
      }
    });
  }, [displayFleet, selectedBusId]);

  // 6. Smooth Motion Simulation Demo along Real Street Polyline
  useEffect(() => {
    if (!isDemoMode) {
      if (demoIntervalRef.current) {
        clearInterval(demoIntervalRef.current);
        demoIntervalRef.current = null;
      }
      return;
    }

    setFleet((prev) => {
      if (prev.length === 0) {
        return [
          {
            id: "SB0003",
            nameBn: "সোনারতরী",
            nameEn: "Sonartori",
            lat: REAL_STREET_POLYLINE[0][0],
            lon: REAL_STREET_POLYLINE[0][1],
            speed: 28,
            heading: 180,
            hasDirection: true,
            routeName: "Campus ➔ Nirala ➔ DC Lake Loop",
            status: "Simulated Road Run",
            color: "#0284c7",
            isDriving: true,
            receivedAt: Date.now()
          }
        ];
      }
      return prev;
    });

    if (!selectedBusId) setSelectedBusId("SB0003");

    let progressIdx = 0;
    demoIntervalRef.current = setInterval(() => {
      progressIdx = (progressIdx + 2) % REAL_STREET_POLYLINE.length;
      const nextCoord = REAL_STREET_POLYLINE[progressIdx];
      const prevCoord = REAL_STREET_POLYLINE[(progressIdx - 1 + REAL_STREET_POLYLINE.length) % REAL_STREET_POLYLINE.length];

      const dLat = nextCoord[0] - prevCoord[0];
      const dLon = nextCoord[1] - prevCoord[1];
      const headingDeg = Math.round((Math.atan2(dLon, dLat) * 180) / Math.PI + 360) % 360;

      setFleet((prev) =>
        prev.map((b) => {
          if (b.id === selectedBusId || (!selectedBusId && b.id === "SB0003")) {
            return {
              ...b,
              lat: nextCoord[0],
              lon: nextCoord[1],
              speed: 32,
              heading: headingDeg,
              hasDirection: true,
              isDriving: true,
              status: "En Route along Corridor",
              receivedAt: Date.now()
            };
          }
          return b;
        })
      );
    }, 1000);

    return () => {
      if (demoIntervalRef.current) {
        clearInterval(demoIntervalRef.current);
        demoIntervalRef.current = null;
      }
    };
  }, [isDemoMode, selectedBusId]);

  // 7. Live MBSTU WebSocket Protocol & Dynamic Discovery
  useEffect(() => {
    if (isDemoMode) return;

    let ws: WebSocket | null = null;
    let heartbeatTimer: NodeJS.Timeout | null = null;
    let isDisposed = false;

    const connect = () => {
      try {
        setWsStatus("connecting");
        ws = new WebSocket("wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket");

        ws.onopen = () => {};

        ws.onmessage = (event) => {
          if (isDisposed) return;
          const raw = String(event.data);

          // 1. Engine.IO Hello -> Send Socket.IO 40 connect
          if (raw.startsWith("0")) {
            ws?.send("40");
            setWsStatus("connected");

            heartbeatTimer = setInterval(() => {
              if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send("2");
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
                const busName = payload.busName || busId;
                const lat = Number(payload.lat);
                const lon = Number(payload.lon || payload.long);
                const reportedSpeed = Number(payload.speed);
                const hasReportedSpeed = payload.speed != null && Number.isFinite(reportedSpeed);
                const speed = hasReportedSpeed ? Math.max(0, reportedSpeed) : 0;
                const reportedHeading = Number(payload.heading);
                const hasReportedHeading = payload.heading != null && Number.isFinite(reportedHeading);
                const heading = hasReportedHeading ? ((reportedHeading % 360) + 360) % 360 : 0;
                const packetReceivedAt = Date.now();
                const receivedAt = parseTelemetryTimestamp(payload.time, packetReceivedAt);
                const time = payload.time || new Date().toLocaleTimeString();

                if (!busId || isNaN(lat) || isNaN(lon)) return;

                const meta = BUS_METADATA[busId] || {
                  nameEn: busName,
                  color: "#0284c7",
                  routeName: "MBSTU Transport Corridor"
                };

                setFleet((prevFleet) => {
                  const existingIdx = prevFleet.findIndex((b) => b.id === busId || b.nameBn === busName);
                  if (existingIdx >= 0) {
                    const updated = [...prevFleet];
                    const previousBus = prevFleet[existingIdx];
                    const elapsedSeconds = previousBus.receivedAt
                      ? (receivedAt - previousBus.receivedAt) / 1000
                      : 0;
                    const distanceSincePrevious = getDistanceMeters(previousBus.lat, previousBus.lon, lat, lon);
                    const inferredSpeed =
                      elapsedSeconds > 0.5 && elapsedSeconds <= 120
                        ? (distanceSincePrevious / elapsedSeconds) * 3.6
                        : previousBus.speed;
                    const finalSpeed = hasReportedSpeed
                      ? speed
                      : inferredSpeed <= 100
                      ? distanceSincePrevious < 2 && elapsedSeconds > 0.5
                        ? 0
                        : inferredSpeed
                      : previousBus.speed;
                    const finalHeading = hasReportedHeading
                      ? heading
                      : distanceSincePrevious > 2
                      ? getBearingDegrees(previousBus.lat, previousBus.lon, lat, lon)
                      : previousBus.heading;
                    const isDriving = payload.isDriving ?? finalSpeed > 0;
                    updated[existingIdx] = {
                      ...updated[existingIdx],
                      lat,
                      lon,
                      speed: finalSpeed,
                      heading: finalHeading,
                      hasDirection: hasReportedHeading || distanceSincePrevious > 2 || previousBus.hasDirection === true,
                      isDriving,
                      status: finalSpeed > 0 ? "In Transit along Corridor" : "Parked at Campus Standby",
                      lastUpdated: time,
                      receivedAt
                    };
                    return updated;
                  } else {
                    const isDriving = payload.isDriving ?? speed > 0;
                    const newBus: MbstuBusData = {
                      id: busId,
                      nameBn: busName,
                      nameEn: meta.nameEn,
                      lat,
                      lon,
                      speed,
                      heading,
                      hasDirection: hasReportedHeading,
                      routeName: meta.routeName,
                      status: speed > 0 ? "In Transit along Corridor" : "Parked at Campus Standby",
                      color: meta.color,
                      isDriving,
                      lastUpdated: time,
                      receivedAt
                    };
                    return [...prevFleet, newBus];
                  }
                });

                setSelectedBusId((curr) => curr || busId);
              }
            } catch (err) {}
          }
        };

        ws.onclose = () => {
          if (!isDisposed) {
            setWsStatus("disconnected");
            setTimeout(connect, 4000);
          }
        };

        ws.onerror = () => {
          if (!isDisposed) {
            setWsStatus("disconnected");
          }
        };
      } catch (err) {
        setWsStatus("disconnected");
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
    <div className="transit-container" style={{ "--bus-accent-color": selectedBus?.color || "#0284c7" } as any}>
      {/* ===================================================================
          Map Canvas (85%+ Viewport)
          =================================================================== */}
      <div className="transit-map-wrapper">
        <div ref={mapContainerRef} className="transit-map-canvas" />
      </div>

      {/* ===================================================================
          A. Top Floating Overlay (z-20)
          =================================================================== */}
      <div className="transit-top-overlay">
        {/* Header Glassmorphic Bar */}
        <div className="transit-header-pill">
          <div className="transit-header-left">
            <div
              className="transit-header-icon-box"
              style={{
                backgroundColor: `${selectedBus?.color || "#0284c7"}25`,
                color: selectedBus?.color || "#38bdf8",
                border: `1px solid ${selectedBus?.color || "#0284c7"}60`
              }}
            >
              <Bus size={20} />
            </div>

            <div className="transit-header-titles">
              <div className="transit-header-title-row">
                <span className="transit-header-main-title">MBSTU Bus Tracker</span>
                <span
                  className={`transit-status-pill ${isDemoMode ? "demo" : wsStatus === "connected" && freshFleetCount > 0 ? "live" : wsStatus === "disconnected" ? "reconnecting" : "waiting"}`}
                  aria-live="polite"
                >
                  <span
                    className="transit-pulse-dot"
                    style={{
                      background: isDemoMode ? "#38bdf8" : wsStatus === "connected" && freshFleetCount > 0 ? "#34d399" : wsStatus === "disconnected" ? "#fb923c" : "#fbbf24"
                    }}
                  />
                  {isDemoMode
                    ? "Road Demo"
                    : wsStatus === "connected" && freshFleetCount > 0
                    ? `Live · ${freshFleetCount} ${freshFleetCount === 1 ? "bus" : "buses"}`
                    : wsStatus === "connected"
                    ? "Waiting for GPS"
                    : wsStatus === "disconnected"
                    ? "Reconnecting"
                    : "Connecting"}
                </span>
              </div>
              <span className="transit-header-sub">Santosh Campus ⇄ Nirala (City Center) Loop</span>
            </div>
          </div>

          {/* Mode Toggle Button */}
          <button
            onClick={() => setIsDemoMode((prev) => !prev)}
            className="transit-mode-toggle-btn"
            title={isDemoMode ? "Return to live bus tracking" : "Preview the map with a simulated bus"}
            aria-label={isDemoMode ? "Return to live tracking" : "Preview road demo"}
          >
            {isDemoMode ? <Radio size={14} color="#38bdf8" /> : <Play size={14} color="#34d399" />}
            <span>{isDemoMode ? "Go live" : "Try demo"}</span>
          </button>
        </div>

        {/* Horizontal Fleet Selector Chips */}
        <div className="transit-chips-scroll" role="group" aria-label="Select a bus">
          {displayFleet.length > 0 ? (
            displayFleet.map((bus) => {
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
                  aria-pressed={isSelected}
                  title={`${bus.nameBn} · ${bus.id}${bus.isStale ? " · GPS delayed" : bus.isEstimated ? " · estimated position" : " · live GPS"}`}
                >
                  <span className="transit-chip-dot" style={{ backgroundColor: bus.color, color: bus.color }} />
                  <span>{bus.nameBn}</span>
                  <span style={{ fontSize: 10, opacity: 0.7, fontFamily: "monospace" }}>{bus.id}</span>
                  <span className={`transit-chip-speed ${bus.isStale ? "stale" : bus.speed > 0 ? "moving" : ""}`}>
                    {bus.isStale ? "Delayed" : bus.speed > 0 ? `${Math.round(bus.speed)} km/h` : "Standby"}
                  </span>
                </button>
              );
            })
          ) : (
            <div className="transit-chip-waiting">
              <span className="transit-pulse-dot" style={{ background: "#fbbf24" }} />
              <span>
                {wsStatus === "connected"
                  ? "Connected · waiting for a bus GPS update"
                  : wsStatus === "disconnected"
                  ? "Connection lost · trying again"
                  : "Connecting to the university GPS feed"}
              </span>
            </div>
          )}
        </div>
      </div>

      {arrivalNotice && (
        <div className="transit-arrival-toast" role="status">
          <CheckCircle2 size={18} />
          <span><strong>{arrivalNotice.busName}</strong> is near {arrivalNotice.stopName}</span>
          <button type="button" onClick={() => setArrivalNotice(null)} aria-label="Dismiss arrival alert">
            <X size={16} />
          </button>
        </div>
      )}

      {/* ===================================================================
          B. Right-Side Floating Map Controls (z-20)
          =================================================================== */}
      <div className={`transit-fab-controls ${sheetExpanded ? "sheet-expanded" : ""}`}>
        {selectedBus && (
          <button
            onClick={centerSelectedBus}
            className="transit-fab-btn"
            title={`Center map on ${selectedBus.nameBn}`}
            aria-label={`Center map on selected bus ${selectedBus.nameBn}`}
          >
            <LocateFixed size={18} color="#38bdf8" />
          </button>
        )}

        <button
          onClick={() => setSatelliteView((prev) => !prev)}
          className="transit-fab-btn"
          title="Toggle Google Satellite / Street Roadmap"
          aria-label={satelliteView ? "Switch to street map" : "Switch to satellite map"}
        >
          <Layers size={18} color={satelliteView ? "#38bdf8" : "#94a3b8"} />
        </button>

        <button
          onClick={recenterCampus}
          className="transit-fab-btn"
          title="Fly to MBSTU Santosh Campus"
          aria-label="Recenter map on campus"
        >
          <MapPin size={18} color="#38bdf8" />
        </button>
      </div>

      {/* ===================================================================
          C. Material 3 Bottom Sheet (z-30) - Non-Squish & Fluid Gestures
          =================================================================== */}
      <div
        className={`transit-bottom-sheet ${sheetExpanded ? "expanded" : "collapsed"}`}
        onTouchStart={handleTouchStart}
        onTouchEnd={handleTouchEnd}
      >
        {/* Drag Handle Bar */}
        <button
          className="transit-sheet-drag-handle"
          onClick={() => setSheetExpanded((prev) => !prev)}
          aria-label={sheetExpanded ? "Collapse transit bottom sheet" : "Expand transit corridor stoppage list"}
        >
          <div className="transit-drag-bar" />
        </button>

        {selectedBus && nextApproachingStopInfo ? (
          <>
            {/* Fixed Header Portion (Never Scrolls, Never Squishes) */}
            <div className="transit-sheet-fixed-header">
              {/* Bus Identity Header Card (Clickable to toggle expand) */}
              <div className="transit-bus-header-card">
                <div className="transit-bus-header-identity">
                  <div
                    className="transit-bus-avatar"
                    style={{
                      background: `linear-gradient(135deg, ${selectedBus.color}dd 0%, ${selectedBus.color} 100%)`,
                      borderColor: selectedBus.color
                    }}
                  >
                    <Bus size={20} color="#ffffff" strokeWidth={2.4} />
                  </div>
                  <div className="transit-bus-identity-meta">
                    <div className="transit-bus-name-row">
                      <span className="transit-bus-name-title">{selectedBus.nameBn}</span>
                      <span
                        className="transit-bus-id-chip"
                        style={{
                          background: `${selectedBus.color}25`,
                          color: selectedBus.color,
                          borderColor: `${selectedBus.color}60`
                        }}
                      >
                        {formatBusShortId(selectedBus.id)}
                      </span>
                    </div>
                    <div className="transit-bus-route-subtitle">{selectedBus.routeName}</div>
                  </div>
                </div>

                <div className="transit-bus-telemetry-right">
                  <span className={`transit-speed-pill ${selectedBus.isStale ? "stale" : selectedBus.speed > 0 ? "moving" : "stopped"}`}>
                    <Compass size={12} />
                    {selectedBus.isStale
                      ? `Last ${Math.round(selectedBus.speed)} km/h`
                      : selectedBus.speed > 0
                      ? `${Math.round(selectedBus.speed)} km/h`
                      : "At stand"}
                  </span>
                  <span className="transit-gps-coords-text">
                    <span title={`${selectedBus.lat.toFixed(5)}, ${selectedBus.lon.toFixed(5)}`}>
                      {selectedBus.lat.toFixed(4)}°N, {selectedBus.lon.toFixed(4)}°E
                    </span>
                    <span className={selectedBus.isStale ? "is-stale" : selectedBus.isEstimated ? "is-estimated" : "is-live-fix"}>
                      {selectedBus.positionAgeSeconds == null
                        ? "Saved · waiting for GPS"
                        : selectedBus.isStale
                        ? `Stale · ${Math.floor(selectedBus.positionAgeSeconds)}s`
                        : selectedBus.isEstimated
                        ? `Estimated · +${Math.floor(selectedBus.positionAgeSeconds)}s`
                        : "GPS · live"}
                    </span>
                  </span>
                </div>
              </div>

              {/* Prominent "Next Approaching Stop" Card */}
              <div className="transit-next-stop-card" style={{ borderColor: selectedBus.color }}>
                <div className="transit-next-left">
                  <div
                    className="transit-next-pin-box"
                    style={{
                      background: `${selectedBus.color}20`,
                      borderColor: `${selectedBus.color}50`,
                      color: selectedBus.color
                    }}
                  >
                    <Navigation size={18} />
                  </div>
                  <div className="transit-next-info">
                    <div className="transit-next-banner-tag">
                      <span className="transit-next-pulse-dot" />
                    <span>
                      {selectedBus.isStale
                        ? "LAST ESTIMATE · GPS DELAYED"
                        : selectedBus.isEstimated
                        ? "PREDICTED NEXT STOP"
                        : "NEXT APPROACHING STOP"}
                    </span>
                    </div>
                    <div className="transit-next-stop-en">{formatStopTitle(nextApproachingStopInfo.station.name)}</div>
                    <div className="transit-next-sub-row">
                      <span className="transit-next-stop-bn">{nextApproachingStopInfo.station.nameBn}</span>
                      <span className="transit-dot-sep">•</span>
                      <span className="transit-next-dist-pill">
                        {nextApproachingStopInfo.distanceKm} km via route
                      </span>
                    </div>
                  </div>
                </div>

                <div className="transit-next-right">
                  <div className="transit-next-eta-container">
                    {selectedBus.isStale ? (
                      <>
                        <span className="transit-next-delayed-badge">GPS DELAYED</span>
                        <span className="transit-next-eta-lbl">Arrival estimate paused</span>
                      </>
                    ) : nextApproachingStopInfo.isStandby ? (
                      <>
                        <span className="transit-next-standby-badge">STANDBY</span>
                        <span className="transit-next-eta-lbl">~{nextApproachingStopInfo.etaMinutes} min if it departs</span>
                      </>
                    ) : (
                      <>
                        <div className="transit-next-eta-val">
                          <span className="transit-next-eta-num">~{nextApproachingStopInfo.etaMinutes}</span>
                          <span className="transit-next-eta-unit">min</span>
                        </div>
                        <span className="transit-next-eta-lbl">Approx. arrival</span>
                      </>
                    )}
                  </div>

                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      toggleStopAlert(nextApproachingStopInfo.station.id);
                    }}
                    className={`transit-bell-alert-btn ${alertedStops[nextApproachingStopInfo.station.id] ? "armed" : ""}`}
                    title={alertedStops[nextApproachingStopInfo.station.id] ? "Turn off in-page arrival alert" : "Alert me while this page is open"}
                    aria-label={alertedStops[nextApproachingStopInfo.station.id]
                      ? `Turn off alert for ${nextApproachingStopInfo.station.name}`
                      : `Alert me when the bus is near ${nextApproachingStopInfo.station.name}`}
                    aria-pressed={!!alertedStops[nextApproachingStopInfo.station.id]}
                  >
                    <Bell size={18} />
                  </button>
                </div>
              </div>

              {/* Sheet Footer Row */}
              <div className="transit-sheet-footer-row">
                <div className="transit-sheet-status-text">
                  <span className={`transit-status-dot ${selectedBus.isStale ? "stale" : selectedBus.isEstimated ? "estimated" : "fresh"}`} />
                  <span>
                    {selectedBus.isStale
                      ? "GPS update delayed"
                      : selectedBus.isEstimated
                      ? "Position estimated between GPS updates"
                      : selectedBus.speed > 0
                      ? "Bus is moving"
                      : "Bus is at the stand"}
                  </span>
                </div>

                <button
                  type="button"
                  onClick={() => setSheetExpanded((prev) => !prev)}
                  className="transit-expand-toggle-btn"
                  aria-expanded={sheetExpanded}
                >
                  <span>{sheetExpanded ? "Hide stops" : "Show route stops"}</span>
                  {sheetExpanded ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
                </button>
              </div>
            </div>

            {/* Scrollable Sequential Corridor Stepper (Visible Only When Expanded) */}
            {sheetExpanded && (
              <div className="transit-corridor-scroll-container">
                <div className="transit-stepper-header">
                  <span>Route stops · {CORRIDOR_STATIONS.length}</span>
                  <span className="transit-stepper-hint">Alerts work while this page is open</span>
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
                        onKeyDown={(event) => {
                          if (event.target !== event.currentTarget) return;
                          if (event.key === "Enter" || event.key === " ") {
                            event.preventDefault();
                            if (mapRef.current) {
                              mapRef.current.flyTo([st.lat, st.lon], 18, { animate: true, duration: 0.8 });
                            }
                          }
                        }}
                        className={`transit-stepper-item ${isApproaching ? "is-approaching" : ""} ${st.isCampus ? "is-campus" : ""}`}
                        role="button"
                        tabIndex={0}
                        aria-label={`Show ${st.name} on map`}
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
                                type="button"
                                title={isAlerted ? "Turn off in-page arrival alert" : "Alert me while this page is open"}
                                aria-label={isAlerted ? `Turn off alert for ${st.name}` : `Alert me when the bus is near ${st.name}`}
                                aria-pressed={isAlerted}
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
                              {selectedBus.isStale
                                ? "GPS delayed"
                                : `~${etaM} min approximate ${selectedBus.speed === 0 ? "drive" : "ETA"}`}
                            </span>
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </>
        ) : (
          /* Waiting State Card */
          <div className="transit-waiting-card">
            <div className="transit-waiting-icon-pulse">
              <Radio size={22} />
            </div>
            <div>
              <div className="transit-waiting-title">
                {wsStatus === "disconnected"
                  ? "Live feed disconnected"
                  : wsStatus === "connected"
                  ? "Waiting for a GPS update"
                  : "Connecting to live tracking"}
              </div>
              <div className="transit-waiting-desc">
                {wsStatus === "disconnected"
                  ? "We’re reconnecting to the university feed. The map will resume when the connection is back."
                  : wsStatus === "connected"
                  ? "The feed is connected. Buses appear when an on-board tracker sends its next location."
                  : "Connecting to the university feed. This can take a few seconds."}
              </div>
              <div style={{ marginTop: 8 }}>
                <button
                  onClick={() => setIsDemoMode(true)}
                  className="transit-mode-toggle-btn"
                  style={{ fontSize: 11, padding: "5px 10px" }}
                >
                  <Play size={13} color="#34d399" />
                  <span>Preview with Road Demo</span>
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

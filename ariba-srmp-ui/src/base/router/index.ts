import {createRouter, createWebHashHistory,type RouteRecordRaw} from 'vue-router'
import Permission from '@/base/router/promise'
import {stores} from "@/base/stores";

const routes: Array<RouteRecordRaw> = [
    {
        path: '/',
        name: "/",
        redirect: () => {
            const cache = stores().cache;
            if (cache.homePage === '/index') {
                cache.homePage = '/inside/index/index';
            }
            return { path: cache.homePage };
        }
    },
    {
        path: '/inside',
        name: "inside",
        component: () => import('@/base/views/inside.vue'),
        meta: {title: '首页'},
        redirect: () => {
            const cache = stores().cache;
            if (cache.homePage === '/index') {
                cache.homePage = '/inside/index/index';
            }
            return { path: cache.homePage };
        },
        children: [],
    },
    {
        path: '/outside',
        name: "outside",
        component: () => import('@/base/views/outside.vue'),
        children: [
            {
                path: '/outside/:pathMatch(.*)*/:pageCode',
                component: () => import('@/base/views/amis.vue'),
            }
        ]
    },
    {
        path: "/:pathMatch(.*)*",
        meta:{pageCode:'404'},
        redirect:"/outside/404",
    },
]

//动态添加page下的所有页面路由
// @ts-ignore
const modules = import.meta.glob('../../custom/views/**/*.vue');
let propertyNames = Object.getOwnPropertyNames(modules);
for (const propertyName of propertyNames) {
    if (propertyName.indexOf("component") != -1) {
        continue;
    }
    let path = propertyName.replaceAll("../../custom/views", "").replaceAll(".vue", "");
    let name = propertyName.replaceAll("../", "").replaceAll(".vue", "");
    let route: RouteRecordRaw = {path: path, name: name, component: modules[propertyName]};
    if (path.startsWith("/inside")) {
        routes.filter(value => value.name === 'inside')[0]?.children?.push(route);
    }
    if (path.startsWith("/outside")) {
        const outsideRoute = routes.filter(value => value.name === 'outside')[0];
        if (outsideRoute && outsideRoute.children) {
            const wildcardIndex = outsideRoute.children.findIndex(
                (child: any) => child.path && child.path.includes(':pathMatch')
            );
            if (wildcardIndex >= 0) {
                outsideRoute.children.splice(wildcardIndex, 0, route);
            } else {
                outsideRoute.children.push(route);
            }
        }
    }
}

routes.filter(value => value.name === 'inside')[0]?.children?.push({
    path: '/iframe',
    component: () => import('@/base/views/iframe.vue'),
}, {
    path: '/:pageCode',
    component: () => import('@/base/views/amis.vue'),
}, {
    path: '/:pathMatch(.*)*/:pageCode',
    component: () => import('@/base/views/amis.vue'),
});

const router = createRouter({
    history: createWebHashHistory(),
    routes
})


//路由守卫
Permission(router);

export default router

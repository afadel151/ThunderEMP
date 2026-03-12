<script setup>
definePageMeta({
    layout: false,
})
</script>

<template>
    <div class="min-h-screen  flex items-center">
        <div class="card mx-auto w-full max-w-5xl  shadow-xl">
            <div class="grid  md:grid-cols-2 grid-cols-1  bg-base rounded-xl">
                <div class="">
                    <div class="hero min-h-full rounded-l-xl bg-base-200">
                        <div class="hero-content py-12">
                            <div class="max-w-md">
                                <h1 class="text-3xl text-center font-bold ">
                                    <img src="https://img.freepik.com/free-vector/privacy-policy-concept-illustration_114360-7853.jpg?w=740&t=st=1705223766~exp=1705224366~hmac=7ca3fbf8bf0efe280c542a27ca99cb2fb49580f9e04f1e397409b5cd040e4862"
                                        class="w-12 inline-block mr-2 mask mask-circle" alt="dashwind-logo" />
                                    DashWind
                                </h1>
                                <div class="text-center mt-12">
                                    <img src="https://img.freepik.com/free-vector/computer-login-concept-illustration_114360-7962.jpg?w=740&t=st=1705223807~exp=1705224407~hmac=6a7e1b3d37caa6155fb689df5e4b86e598db633aaa841ac84a9576363f76c66d"
                                        alt="Dashwind Admin Template" class="w-48 inline-block"></img>
                                </div>

                                <!-- <FeatureList /> -->
                            </div>
                        </div>
                    </div>
                </div>
                <div class="py-24 px-10">
                    <h2 class="text-2xl font-semibold mb-2 text-center">Login</h2>
                    <form>
                        <div class='form-control w-full mt-4'>
                            <label class="label">
                                <span class='label-text text-base-content '>Username</span>
                            </label>
                            <input type=' text' placeholder='' class="input  w-full" />
                        </div>

                        <div class='form-control w-full mt-4'>
                            <label class="label">
                                <span class='label-text text-base-content'>Secret Code</span>
                            </label>
                            <input type='password' placeholder='' class="input  w-full" />
                        </div>

                        <div class="text-right text-primary">
                            <a href="/forgot-password">
                                <span
                                    class="text-sm  inline-block  hover:text-primary hover:underline hover:cursor-pointer transition duration-200">
                                    Forgot Secret Code?
                                </span>
                            </a>
                        </div>


                        <p class='text-center text-error mt-8'>
                            Change the secret message
                        </p>


                        <button type="submit" class='btn mt-2 w-full btn-primary'>
                            Access Dashboard
                        </button>

                        <div class="text-center mt-4">

                            <a href="/register">
                                <span
                                    class="  inline-block  hover:text-primary hover:underline hover:cursor-pointer transition duration-200">
                                    Join Now
                                </span>
                            </a>
                        </div>
                    </form>
                </div>
            </div>
        </div>
    </div>

</template>


<!-- 

import React from 'react';

const FeatureList = () => {
  const features = [
    'Dark/light mode switch',
    'Redux toolkit and other utility libraries set up',
    'Calendar, Modal, Sidebar components included',
    'User-friendly documentation provided',
    'Daisy UI components and Tailwind CSS supported',
  ];

  return (
    <>
      <h1 class="text-2xl mt-8 font-bold">Admin Dashboard Starter Kit</h1>
      {features.map((feature, index) => (
        <p key={index} class="py-2">
          ✓ <span class="font-semibold" dangerouslySetInnerHTML={{ __html: feature }} />
        </p>
      ))}
    </>
  );
};

const LoginForm = () => {
  return (
    <div class="py-24 px-10">
      <h2 class="text-2xl font-semibold mb-2 text-center">Login</h2>
      <form>
        {/* Email Input */}
        <div class={`form-control w-full mt-4'>
          <label class="label">
            <span class={'label-text text-base-content '}>Username</span>
          </label>
          <input
            type={'text'}
            placeholder={''}
            class="input  input-bordered w-full"
          />
        </div>

        {/* Password Input */}
        <div class={`form-control w-full mt-4'>
          <label class="label">
            <span class={'label-text text-base-content '}>Secret Code</span>
          </label>
          <input
            type={'password'}
            placeholder={''}
            class="input  input-bordered w-full"
          />
        </div>

        {/* Forgot Password Link */}
        <div class="text-right text-primary">
          <a href="/forgot-password">
            <span class="text-sm  inline-block  hover:text-primary hover:underline hover:cursor-pointer transition duration-200">
              Forgot Secret Code?
            </span>
          </a>
        </div>

        {/* Error Message */}
        <p class={`text-center  text-error mt-8'>
          Change the secret message
        </p>

        {/* Login Button */}
        <button type="submit" class={`btn mt-2 w-full btn-primary'>
          Access Dashboard
        </button>

        {/* Register Link */}
        <div class="text-center mt-4">
          Don't have an account yet?{' '}
          <a href="/register">
            <span class="  inline-block  hover:text-primary hover:underline hover:cursor-pointer transition duration-200">
              Join Now
            </span>
          </a>
        </div>
      </form>
    </div>
  );
};

const Login = () => {
  return (
    <div class="min-h-screen bg-base-200 flex items-center">
      <div class="card mx-auto w-full max-w-5xl  shadow-xl">
        <div class="grid  md:grid-cols-2 grid-cols-1  bg-base-100 rounded-xl">
          <div class="">
            <div class="hero min-h-full rounded-l-xl bg-base-200">
              <div class="hero-content py-12">
                <div class="max-w-md">
                  <h1 class="text-3xl text-center font-bold ">
                    <img
                      src="https://img.freepik.com/free-vector/privacy-policy-concept-illustration_114360-7853.jpg?w=740&t=st=1705223766~exp=1705224366~hmac=7ca3fbf8bf0efe280c542a27ca99cb2fb49580f9e04f1e397409b5cd040e4862"
                      class="w-12 inline-block mr-2 mask mask-circle"
                      alt="dashwind-logo"
                    />
                    DashWind
                  </h1>
                  <div class="text-center mt-12">
                    <img
                      src="https://img.freepik.com/free-vector/computer-login-concept-illustration_114360-7962.jpg?w=740&t=st=1705223807~exp=1705224407~hmac=6a7e1b3d37caa6155fb689df5e4b86e598db633aaa841ac84a9576363f76c66d"
                      alt="Dashwind Admin Template"
                      class="w-48 inline-block"
                    ></img>
                  </div>
                  {/* Importing pointers component */}
                  <FeatureList />
                </div>
              </div>
            </div>{' '}
          </div>
          <LoginForm />
        </div>
      </div>
    </div>
  );
};

export default Login;


-->